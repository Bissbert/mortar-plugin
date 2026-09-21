package ch.bissbert.mortar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.logging.Level;

/** All world interaction is confined to Paper's primary thread. No TNT entities or timed fuses. */
public final class MortarPlugin extends JavaPlugin implements Listener, TabExecutor {
    enum Munition {
        HE("he", "HE Mortar Grenade", "grenade_he", NamedTextColor.RED),
        PENETRATING("penetrating", "Penetrating Mortar Grenade", "grenade_penetrating", NamedTextColor.GOLD),
        DEPTH_CHARGE("depth_charge", "THE DEPTH CHARGE", "grenade_penetrating", NamedTextColor.DARK_PURPLE),
        AIRBURST("airburst", "Airburst Mortar Grenade", "grenade_airburst", NamedTextColor.AQUA),
        CLUSTER("cluster", "Cluster Mortar Grenade", "grenade_he", NamedTextColor.LIGHT_PURPLE),
        INCENDIARY("incendiary_grenade", "Incendiary Mortar Grenade", "grenade_he", NamedTextColor.DARK_RED),
        SMOKE("smoke", "Smoke Mortar Grenade", "grenade_airburst", NamedTextColor.GRAY),
        ILLUMINATION("illumination", "Illumination Flare", "grenade_airburst", NamedTextColor.YELLOW);
        final String id, title, model;
        final NamedTextColor color;
        Munition(String id, String title, String model, NamedTextColor color) {
            this.id = id; this.title = title; this.model = model; this.color = color;
        }
        static Munition parse(String value) {
            return Arrays.stream(values()).filter(m -> m.id.equalsIgnoreCase(value)).findFirst().orElse(null);
        }
    }

    private NamespacedKey mortarKey, ammoKey, visualKey;
    private Settings settings;
    private SharedLeases<ChunkKey> tickets;
    private final Map<UUID, Shot> shots = new LinkedHashMap<>();
    private final Set<Effect> effects = new LinkedHashSet<>();
    private final Map<UUID, Location> targets = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<Block, TemporaryLight> lights = new HashMap<>();
    private long tick;

    @Override public void onEnable() {
        mortarKey = new NamespacedKey(this, "mortar");
        ammoKey = new NamespacedKey(this, "munition");
        visualKey = new NamespacedKey(this, "visual");
        saveDefaultConfig(); settings = readSettings();
        tickets = new SharedLeases<>(key -> {
            World world = Bukkit.getWorld(key.world);
            if (world == null || !world.isChunkGenerated(key.x, key.z)) return false;
            if (!world.loadChunk(key.x, key.z, false)) return false;
            world.addPluginChunkTicket(key.x, key.z, this);
            return true;
        }, key -> {
            World world = Bukkit.getWorld(key.world);
            if (world != null) world.removePluginChunkTicket(key.x, key.z, this);
        });
        PluginCommand command = Objects.requireNonNull(getCommand("mortar"));
        command.setExecutor(this); command.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) cleanupStale(entity);
        Bukkit.getScheduler().runTaskTimer(this, this::advance, 1, 1);
        getLogger().info("Mortar ready: target-lock ballistics, eight munitions, no artificial flight expiry.");
    }

    @Override public void onDisable() {
        shots.values().forEach(Shot::cleanup); shots.clear();
        List.copyOf(effects).forEach(Effect::cleanup); effects.clear();
        targets.clear(); cooldowns.clear();
        if (tickets != null) tickets.clear();
    }

    private double number(String key, double fallback, double min, double max) {
        double value = getConfig().getDouble(key, fallback);
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }

    private Settings readSettings() {
        EnumMap<Munition, Payload> payloads = new EnumMap<>(Munition.class);
        for (Munition type : Munition.values()) {
            String p = "munitions." + type.id + ".";
            double power = switch (type) {
                case HE, PENETRATING -> 8; case AIRBURST -> 7; case CLUSTER -> 1.8;
                case INCENDIARY -> 3; default -> 0;
            };
            double height = switch (type) { case AIRBURST -> 8; case CLUSTER -> 16; case ILLUMINATION -> 12; default -> 0; };
            List<Double> depths = getConfig().getDoubleList(p + "depths");
            List<Double> powers = getConfig().getDoubleList(p + "powers");
            if (depths.isEmpty() || depths.size() != powers.size() || depths.size() > 16
                    || depths.stream().anyMatch(v -> !Double.isFinite(v) || v < 0 || v > 64)
                    || powers.stream().anyMatch(v -> !Double.isFinite(v) || v < 0 || v > 16)) {
                depths = List.of(4d, 6d, 8d, 10d); powers = List.of(9d, 8d, 7d, 6d);
            }
            payloads.put(type, new Payload(type, (float) number(p + "power", power, 0, 16),
                    number(p + "depth", type == Munition.PENETRATING ? 8 : 0, 0, 64),
                    number(p + "height", height, 0, 64), List.copyOf(depths), List.copyOf(powers),
                    (int) number(p + "interval-ticks", type == Munition.DEPTH_CHARGE ? 4 : 2, 1, 100),
                    (int) number(p + "count", 8, 1, 32), number(p + "radius", type == Munition.SMOKE ? 6 : 5, 1, 24),
                    (int) number(p + "duration-ticks", type == Munition.SMOKE ? 400 : 600, 1, 6000),
                    number(p + "ground-search-distance", 32, 1, 128),
                    (int) number(p + "light-level", 15, 1, 15),
                    (int) number(p + "light-height", 6, 1, 12), (int) number(p + "light-spacing", 8, 1, 16),
                    (int) number(p + "density", type == Munition.SMOKE ? 240 : 8, 8, 400),
                    number(p + "smoke-height", type == Munition.SMOKE ? 6 : 0, 0, 16),
                    number(p + "smoke-drift", type == Munition.SMOKE ? .025 : 0, 0, .2),
                    (int) number(p + "fire-radius", type == Munition.INCENDIARY ? 6 : 0, 0, 12),
                    (int) number(p + "terrain-radius", type == Munition.INCENDIARY ? 2 : 0, 0, 8)));
        }
        return new Settings(number("gravity", .01, .0001, .5), (int) number("cooldown-ticks", 50, 1, 1200),
                number("arc-clearance", 64, 8, 256), number("target-distance", 512, 16, 2048),
                (int) number("max-active-projectiles", 100, 1, 500),
                (int) number("max-active-effects", 100, 1, 500), (int) number("ammo-per-shot", 1, 1, 64),
                getConfig().getBoolean("block-damage", true), getConfig().getBoolean("set-fire", false), payloads);
    }

    private boolean isMortar(ItemStack item) {
        return item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(mortarKey, PersistentDataType.BYTE);
    }

    private Munition munition(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return Munition.parse(item.getItemMeta().getPersistentDataContainer().get(ammoKey, PersistentDataType.STRING));
    }

    private ItemStack item(Munition type, int count) {
        ItemStack stack = new ItemStack(type == null ? Material.BLAZE_ROD : Material.FIREWORK_STAR, count);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(type == null ? "Mortar" : type.title,
                type == null ? NamedTextColor.DARK_PURPLE : type.color).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(type == null ? "Right-click: target. Left-click: fire." : "Hold in offhand to select this shell.",
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        // Vanilla ignores these strings without the optional pack and renders the base item normally.
        var model = meta.getCustomModelDataComponent();
        model.setStrings(List.of("mortar:" + (type == null ? "tnt_mortar" : type.model)));
        meta.setCustomModelDataComponent(model);
        if (type == null) {
            meta.getPersistentDataContainer().set(mortarKey, PersistentDataType.BYTE, (byte) 1);
            meta.setMaxStackSize(1);
        } else meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.STRING, type.id);
        stack.setItemMeta(meta); return stack;
    }

    private boolean canUse(Player player) {
        if (!player.hasPermission("mortar.use")) { message(player, "You do not have permission to use a mortar."); return false; }
        return !player.isDead() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.CREATIVE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isMortar(event.getItem())) return;
        Action action = event.getAction();
        if (action == Action.PHYSICAL) return;
        // Air use is pre-cancelled by vanilla for blaze rods; block denial must still be honored.
        if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK)
                && event.useItemInHand() == Event.Result.DENY) return;
        event.setCancelled(true);
        if (!canUse(event.getPlayer())) return;
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) selectTarget(event.getPlayer());
        else if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) fire(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isMortar(event.getPlayer().getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        if (canUse(event.getPlayer())) selectTarget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void attackEntity(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (!(event.getDamager() instanceof Player player) || !isMortar(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        if (canUse(player)) fire(player);
    }

    private void selectTarget(Player player) {
        Location eye = player.getEyeLocation(); Vector direction = eye.getDirection();
        // Trace in loaded chunks only: targeting cannot generate unseen terrain.
        for (double distance = 0; distance < settings.targetDistance; distance += 4) {
            double length = Math.min(4, settings.targetDistance - distance);
            Location start = eye.clone().add(direction.clone().multiply(distance));
            Location end = start.clone().add(direction.clone().multiply(length));
            if (chunksBetween(start, end, .25).stream().anyMatch(k -> !eye.getWorld().isChunkLoaded(k.x, k.z))) break;
            RayTraceResult hit = trace(eye.getWorld(), start, direction, length, player.getUniqueId());
            if (hit != null) {
                Location target = hit.getHitPosition().toLocation(eye.getWorld());
                targets.put(player.getUniqueId(), target);
                Ballistics.Solution solution = solve(eye, target, settings);
                player.spawnParticle(Particle.END_ROD, target.clone().add(0, .3, 0), 15, .3, .3, .3, .02);
                message(player, "Target " + target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ()
                        + " | " + Math.round(eye.distance(target)) + " blocks | " + String.format(Locale.ROOT, "%.1fs", solution.flightTicks() / 20d));
                return;
            }
        }
        message(player, "No visible target in loaded terrain within " + (int) settings.targetDistance + " blocks.");
    }

    private Ballistics.Solution solve(Location start, Location target, Settings options) {
        return Ballistics.solve(target.getX() - start.getX(), target.getY() - start.getY(),
                target.getZ() - start.getZ(), options.gravity, options.clearance);
    }

    private void fire(Player player) {
        if (!canUse(player)) return;
        Location target = targets.get(player.getUniqueId());
        if (target == null || !target.getWorld().equals(player.getWorld())) { message(player, "Right-click a visible target first."); return; }
        if (tick < cooldowns.getOrDefault(player.getUniqueId(), 0L)) return;
        if (shots.size() >= settings.maxShots || effects.size() >= settings.maxEffects) { message(player, "Mortar activity limit reached; wait for current shells/effects."); return; }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        Munition selected = munition(offhand);
        boolean free = player.getGameMode() == GameMode.CREATIVE || player.hasPermission("mortar.infiniteammo");
        if (!free && (selected == null ? countTnt(player) < settings.ammoPerShot : offhand.getAmount() < settings.ammoPerShot)) {
            message(player, "Requires " + settings.ammoPerShot + (selected == null ? " plain TNT in inventory." : " selected shell(s) in offhand.")); return;
        }
        Location start = player.getEyeLocation();
        Ballistics.Solution solution = solve(start, target, settings);
        Shot shot = new Shot(player.getUniqueId(), start, new Vector(solution.x(), solution.y(), solution.z()),
                settings, settings.payloads.get(selected == null ? Munition.HE : selected));
        if (!tickets.replace(shot.held, chunksBetween(start, start, 1))) { message(player, "Launch chunk unavailable."); return; }
        try {
            shot.display = visual(start, Material.TNT, .45f);
            shots.put(shot.id, shot);
        } catch (RuntimeException failure) { shot.cleanup(); throw failure; }
        if (!free) {
            if (selected != null) {
                offhand.setAmount(offhand.getAmount() - settings.ammoPerShot);
                player.getInventory().setItemInOffHand(offhand.getAmount() == 0 ? null : offhand);
            } else consumeTnt(player, settings.ammoPerShot);
        }
        cooldowns.put(player.getUniqueId(), tick + settings.cooldown);
        start.getWorld().playSound(start, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, .8f, .55f);
        start.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, start, 6, .15, .15, .15, .01);
        player.swingMainHand();
    }

    private int countTnt(Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) if (plainTnt(stack)) count += stack.getAmount();
        if (plainTnt(player.getInventory().getItemInOffHand())) count += player.getInventory().getItemInOffHand().getAmount();
        return count;
    }
    private boolean plainTnt(ItemStack stack) { return stack != null && stack.getType() == Material.TNT && !stack.hasItemMeta(); }
    private void consumeTnt(Player player, int remaining) {
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!plainTnt(stack)) continue;
            int take = Math.min(remaining, stack.getAmount()); remaining -= take;
            stack.setAmount(stack.getAmount() - take); player.getInventory().setItem(slot, stack.getAmount() == 0 ? null : stack);
        }
        if (remaining > 0) {
            ItemStack stack = player.getInventory().getItemInOffHand();
            stack.setAmount(stack.getAmount() - remaining); player.getInventory().setItemInOffHand(stack.getAmount() == 0 ? null : stack);
        }
    }

    private void advance() {
        tick++;
        for (Shot shot : List.copyOf(shots.values())) {
            if (!shots.containsKey(shot.id)) continue;
            try { if (shot.step()) continue; }
            catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Removed failed mortar shell", failure); }
            shots.remove(shot.id); shot.cleanup();
        }
        for (Effect effect : List.copyOf(effects)) {
            if (!effects.contains(effect)) continue;
            try { if (effect.step()) continue; }
            catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Removed failed mortar effect", failure); }
            effects.remove(effect); effect.cleanup();
        }
    }

    private RayTraceResult trace(World world, Location start, Vector direction, double distance, UUID owner) {
        return world.rayTrace(start, direction, distance, FluidCollisionMode.ALWAYS, false, .225,
                e -> e.isValid() && !e.getUniqueId().equals(owner)
                        && (e instanceof LivingEntity || e instanceof Vehicle || e instanceof Hanging)
                        && (!(e instanceof Player p) || p.getGameMode() != GameMode.SPECTATOR));
    }

    private final class Shot {
        final UUID id = UUID.randomUUID(), owner;
        final Settings options;
        final Payload payload;
        final Set<ChunkKey> held = new HashSet<>();
        final Vector velocity;
        Location position;
        BlockDisplay display;
        boolean finished;
        Shot(UUID owner, Location position, Vector velocity, Settings options, Payload payload) {
            this.owner = owner; this.position = position; this.velocity = velocity; this.options = options; this.payload = payload;
        }
        boolean step() {
            World world = position.getWorld(); Player shooter = validOwner(owner, world);
            if (finished || shooter == null || display == null || !display.isValid()) return false;
            Location next = position.clone().add(velocity);
            if (!inFlightBounds(next) || !tickets.replace(held, chunksBetween(position, next, 1))) return false;
            double distance = velocity.length();
            // A vertical shell may have zero speed for a tick at its apex. Gravity must continue.
            if (distance > 1e-10) {
                Vector direction = velocity.clone().normalize();
                RayTraceResult hit = trace(world, position, direction, distance, owner);
                if (payload.height > 0 && velocity.getY() < 0) {
                    RayTraceResult ground = world.rayTraceBlocks(position, new Vector(0, -1, 0), payload.height,
                            FluidCollisionMode.ALWAYS, false);
                    if (ground != null) return detonate(shooter, position, direction, false);
                }
                if (hit != null) {
                    Location impact = hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(.04));
                    return detonate(shooter, impact, direction, hit.getHitBlock() != null);
                }
            }
            position = next; display.teleport(position);
            world.spawnParticle(Particle.SMOKE, position, 1, 0, 0, 0, 0);
            velocity.setY(velocity.getY() - options.gravity);
            return true;
        }
        boolean detonate(Player shooter, Location at, Vector direction, boolean blockHit) {
            if (finished) return false;
            finished = true;
            if (payload.type == Munition.DEPTH_CHARGE && blockHit) addEffect(new BlastSequence(owner, at, options, payload, direction));
            else if (payload.type == Munition.CLUSTER) addEffect(new BlastSequence(owner, at, options, payload, direction));
            else if (payload.type == Munition.SMOKE || payload.type == Munition.ILLUMINATION)
                addEffect(new UtilityEffect(owner, at, options, payload));
            else {
                if (payload.type == Munition.PENETRATING && blockHit) at = at.clone().add(direction.clone().multiply(payload.depth));
                float power = payload.type == Munition.DEPTH_CHARGE ? payload.powers.getFirst().floatValue() : payload.power;
                Set<ChunkKey> wanted = new HashSet<>(held); wanted.addAll(blastChunks(at, power));
                if (inBlockBounds(at) && tickets.replace(held, wanted)) explode(shooter, at, power, options,
                        payload.type == Munition.INCENDIARY, payload.fireRadius, payload.terrainRadius);
            }
            return false;
        }
        void cleanup() { finished = true; if (display != null) display.remove(); tickets.releaseAll(held); }
    }

    private void addEffect(Effect effect) {
        if (effects.size() >= settings.maxEffects) { effect.cleanup(); return; }
        effects.add(effect);
    }

    private abstract class Effect {
        final UUID owner;
        final Location origin;
        final Settings options;
        final Payload payload;
        final Set<ChunkKey> held = new HashSet<>();
        final List<Entity> entities = new ArrayList<>();
        final Set<Block> ownedLights = new HashSet<>();
        int age;
        boolean closed;
        Effect(UUID owner, Location origin, Settings options, Payload payload) {
            this.owner = owner; this.origin = origin.clone(); this.options = options; this.payload = payload;
        }
        final boolean step() {
            Player shooter = validOwner(owner, origin.getWorld());
            if (closed || shooter == null) return false;
            boolean alive = update(shooter); age++; return alive && !closed;
        }
        abstract boolean update(Player shooter);
        void cleanup() {
            if (closed) return; closed = true;
            entities.forEach(Entity::remove); entities.clear();
            for (Block block : ownedLights) {
                TemporaryLight light = lights.get(block);
                if (light == null) continue;
                if (--light.references == 0) {
                    if (block.getBlockData().matches(light.placed)) block.setBlockData(light.original, false);
                    lights.remove(block);
                }
            }
            ownedLights.clear(); tickets.releaseAll(held);
        }
    }

    private final class BlastSequence extends Effect {
        final List<Location> stages = new ArrayList<>();
        final List<Float> powers = new ArrayList<>();
        BlastSequence(UUID owner, Location origin, Settings options, Payload payload, Vector direction) {
            super(owner, origin, options, payload);
            int count = payload.type == Munition.DEPTH_CHARGE ? payload.depths.size() : payload.count;
            for (int i = 0; i < count; i++) {
                Location at = origin.clone();
                if (payload.type == Munition.DEPTH_CHARGE) at.add(direction.clone().multiply(payload.depths.get(i)));
                else at.add(Math.cos(i * Math.PI * 2 / count) * payload.radius, 0,
                        Math.sin(i * Math.PI * 2 / count) * payload.radius);
                stages.add(at); powers.add(payload.type == Munition.DEPTH_CHARGE ? payload.powers.get(i).floatValue() : payload.power);
            }
        }
        boolean update(Player shooter) {
            if (age == 0) {
                Set<ChunkKey> wanted = new HashSet<>();
                for (int i = 0; i < stages.size(); i++) {
                    if (!inFlightBounds(stages.get(i))) return false;
                    wanted.addAll(blastChunks(stages.get(i), powers.get(i)));
                }
                if (!tickets.replace(held, wanted)) return false;
            }
            if (age % payload.interval == 0) {
                int stage = age / payload.interval;
                if (stage >= stages.size()) return false;
                Location at = stages.get(stage);
                if (payload.type == Munition.CLUSTER) {
                    RayTraceResult ground = at.getWorld().rayTraceBlocks(at, new Vector(0, -1, 0), payload.groundSearch,
                            FluidCollisionMode.ALWAYS, false);
                    if (ground != null) {
                        at.getWorld().spawnParticle(Particle.FLAME, at, 8, .2, 1, .2, .03);
                        explode(shooter, ground.getHitPosition().toLocation(at.getWorld()), powers.get(stage), options, false, 0, 0);
                    }
                } else if (inBlockBounds(at)) explode(shooter, at, powers.get(stage), options, false, 0, 0);
                return stage + 1 < stages.size();
            }
            return true;
        }
    }

    private final class UtilityEffect extends Effect {
        UtilityEffect(UUID owner, Location origin, Settings options, Payload payload) { super(owner, origin, options, payload); }
        boolean update(Player shooter) {
            if (age == 0) {
                int radius = payload.type == Munition.ILLUMINATION ? payload.lightSpacing + 2 : (int) Math.ceil(payload.radius + 1);
                if (!tickets.replace(held, chunksBetween(origin, origin, radius))) return false;
                if (payload.type != Munition.SMOKE) {
                    BlockDisplay marker = visual(origin, Material.SEA_LANTERN, .5f);
                    marker.setGlowing(true); marker.setBrightness(new Display.Brightness(15, 15)); entities.add(marker);
                    RayTraceResult ground = origin.getWorld().rayTraceBlocks(origin, new Vector(0, -1, 0), 32,
                            FluidCollisionMode.ALWAYS, false);
                    Location center = ground == null ? origin.clone().subtract(0, 6, 0)
                            : ground.getHitPosition().toLocation(origin.getWorld()).add(0, payload.lightHeight, 0);
                    int spacing = payload.lightSpacing;
                    for (int[] offset : new int[][]{{0,0}, {spacing,0}, {-spacing,0}, {0,spacing}, {0,-spacing}}) {
                        Location at = center.clone().add(offset[0], 0, offset[1]);
                        if (!inBlockBounds(at)) continue;
                        Block block = at.getBlock(); TemporaryLight existing = lights.get(block);
                        if (existing != null && block.getBlockData().matches(existing.placed)) {
                            existing.references++; ownedLights.add(block); continue;
                        }
                        if (!block.getType().isAir()) continue;
                        BlockData before = block.getBlockData(); Light data = (Light) Material.LIGHT.createBlockData();
                        data.setLevel(payload.lightLevel); block.setBlockData(data, false);
                        lights.put(block, new TemporaryLight(before, data)); ownedLights.add(block);
                    }
                }
            }
            // Smoke is a persistent volume rather than a one-tick impact marker. Re-emit
            // the layered particles every tick so the cloud stays full and readable as it drifts.
            if (payload.type == Munition.SMOKE) spawnSmoke(origin.getWorld());
            if (payload.type == Munition.ILLUMINATION && age % 5 == 0)
                origin.getWorld().spawnParticle(Particle.END_ROD, origin, 3, .2, .3, .2, .01);
            return age < payload.duration;
        }

        private void spawnSmoke(World world) {
            // A single AreaEffectCloud renders as a mostly flat disk. Layered particle volumes
            // make the cloud occupy real 3-D space, while the moving center leaves a slow wind trail.
            double driftTicks = age;
            double driftX = payload.smokeDrift * driftTicks;
            double driftZ = payload.smokeDrift * .35 * driftTicks;
            double swirlX = Math.sin(age * .075) * .35;
            double swirlZ = Math.cos(age * .055) * .35;
            Location base = origin.clone().add(driftX + swirlX, 0, driftZ + swirlZ);
            int layers = Math.max(3, (int) Math.ceil(payload.smokeHeight / 1.25));
            int perLayer = Math.max(4, (int) Math.ceil(payload.smokeDensity / (double) layers));
            for (int layer = 0; layer < layers; layer++) {
                double fraction = (layer + .5) / layers;
                Location center = base.clone().add(0, payload.smokeHeight * fraction, 0);
                double layerRadius = payload.radius * (.88 + .12 * fraction);
                double layerHeight = Math.max(.65, payload.smokeHeight / layers * .75);
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center, perLayer,
                        layerRadius * .72, layerHeight, layerRadius * .72, .008);
                // Signal smoke is larger and slower, filling the interior instead of only outlining it.
                world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, center, Math.max(4, perLayer / 2),
                        layerRadius * .55, layerHeight * .8, layerRadius * .55, .004);
            }
            world.spawnParticle(Particle.LARGE_SMOKE, base.clone().add(0, payload.smokeHeight * .45, 0),
                    Math.max(10, perLayer), payload.radius * .55, payload.smokeHeight * .45,
                    payload.radius * .55, .002);
        }
    }

    private void explode(Player shooter, Location at, float power, Settings options, boolean incendiary,
                         int fireRadius, int terrainRadius) {
        if (!inBlockBounds(at) || !shooter.isOnline() || shooter.isDead() || !shooter.getWorld().equals(at.getWorld())) return;
        // Keep the full-power explosion for entity damage/knockback, but do not let that
        // same power destroy terrain. Incendiaries get only a shallow hand-sized crater.
        boolean exploded = at.getWorld().createExplosion(shooter, at, power, options.fire || incendiary,
                options.blockDamage && !incendiary, false);
        if (incendiary && exploded) {
            if (options.blockDamage) scorchTerrain(at, terrainRadius);
            leaveIncendiaryFire(at, power, fireRadius);
        }
    }

    private void scorchTerrain(Location origin, int configuredRadius) {
        World world = origin.getWorld();
        int radius = Math.max(1, Math.min(8, configuredRadius));
        int centerX = origin.getBlockX(), centerY = origin.getBlockY(), centerZ = origin.getBlockZ();
        int minY = Math.max(world.getMinHeight(), centerY - 3);
        int maxY = Math.min(world.getMaxHeight() - 1, centerY + 1);
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz > radius * radius) continue;
            int x = centerX + dx, z = centerZ + dz;
            for (int y = maxY; y >= minY; y--) {
                Block block = world.getBlockAt(x, y, z);
                if (!block.getType().isSolid()) continue;
                block.setType(Material.AIR, false);
                break;
            }
        }
    }

    private void leaveIncendiaryFire(Location origin, float power, int configuredRadius) {
        World world = origin.getWorld();
        int radius = configuredRadius > 0 ? configuredRadius : (int) Math.ceil(power * 1.5);
        radius = Math.max(1, Math.min(12, radius));
        int centerX = origin.getBlockX(), centerY = origin.getBlockY(), centerZ = origin.getBlockZ();
        int minY = Math.max(world.getMinHeight(), centerY - 8);
        int maxY = Math.min(world.getMaxHeight() - 2, centerY + 3);
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz > radius * radius) continue;
            int x = centerX + dx, z = centerZ + dz;
            for (int y = maxY; y >= minY; y--) {
                Block ground = world.getBlockAt(x, y, z);
                Block flame = world.getBlockAt(x, y + 1, z);
                if (!ground.getType().isSolid() || !flame.getType().isAir()) continue;
                flame.setType(Material.FIRE, false);
                break;
            }
        }
    }

    private BlockDisplay visual(Location at, Material material, float scale) {
        return at.getWorld().spawn(at, BlockDisplay.class, entity -> {
            tag(entity); entity.setBlock(material.createBlockData()); entity.setInvulnerable(true);
            entity.setGravity(false); entity.setSilent(true); entity.setTeleportDuration(1); entity.setViewRange(1.5f);
            entity.setTransformation(new Transformation(new Vector3f(-scale / 2), new Quaternionf(), new Vector3f(scale), new Quaternionf()));
        });
    }
    private void tag(Entity entity) {
        entity.setPersistent(false); entity.getPersistentDataContainer().set(visualKey, PersistentDataType.BYTE, (byte) 1);
    }
    private void cleanupStale(Entity entity) {
        if (entity.getPersistentDataContainer().has(visualKey, PersistentDataType.BYTE)
                && shots.values().stream().noneMatch(s -> s.display != null && s.display.getUniqueId().equals(entity.getUniqueId()))
                && effects.stream().noneMatch(e -> e.entities.contains(entity))) entity.remove();
    }
    private Player validOwner(UUID owner, World world) {
        Player player = Bukkit.getPlayer(owner);
        return player != null && player.isOnline() && !player.isDead() && player.getWorld().equals(world) ? player : null;
    }
    private boolean inFlightBounds(Location at) {
        return Double.isFinite(at.getX()) && Double.isFinite(at.getY()) && Double.isFinite(at.getZ())
                && at.getY() >= at.getWorld().getMinHeight() && at.getWorld().getWorldBorder().isInside(at);
    }
    private boolean inBlockBounds(Location at) { return inFlightBounds(at) && at.getY() < at.getWorld().getMaxHeight(); }
    private Set<ChunkKey> blastChunks(Location at, float power) { return chunksBetween(at, at, Math.ceil(power * 2 + 4)); }
    private Set<ChunkKey> chunksBetween(Location from, Location to, double padding) {
        Set<ChunkKey> result = new HashSet<>();
        int minX = ((int) Math.floor(Math.min(from.getX(), to.getX()) - padding)) >> 4;
        int maxX = ((int) Math.floor(Math.max(from.getX(), to.getX()) + padding)) >> 4;
        int minZ = ((int) Math.floor(Math.min(from.getZ(), to.getZ()) - padding)) >> 4;
        int maxZ = ((int) Math.floor(Math.max(from.getZ(), to.getZ()) + padding)) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) result.add(new ChunkKey(from.getWorld().getUID(), x, z));
        return result;
    }
    private void message(Player player, String text) { player.sendActionBar(Component.text(text, NamedTextColor.GOLD)); }

    @EventHandler public void quit(PlayerQuitEvent event) { cleanupOwner(event.getPlayer().getUniqueId()); }
    @EventHandler public void death(PlayerDeathEvent event) { cleanupOwner(event.getEntity().getUniqueId()); }
    @EventHandler public void worldChange(PlayerChangedWorldEvent event) { cleanupOwner(event.getPlayer().getUniqueId()); }
    private void cleanupOwner(UUID owner) {
        shots.values().removeIf(s -> { if (!s.owner.equals(owner)) return false; s.cleanup(); return true; });
        effects.removeIf(e -> { if (!e.owner.equals(owner)) return false; e.cleanup(); return true; });
        targets.remove(owner); cooldowns.remove(owner);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void worldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        shots.values().removeIf(s -> { if (!s.position.getWorld().equals(world)) return false; s.cleanup(); return true; });
        effects.removeIf(e -> { if (!e.origin.getWorld().equals(world)) return false; e.cleanup(); return true; });
        targets.values().removeIf(t -> t.getWorld().equals(world));
    }
    @EventHandler public void entitiesLoad(EntitiesLoadEvent event) { event.getEntities().forEach(this::cleanupStale); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mortar.admin")) { sender.sendMessage("You do not have permission."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig(); settings = readSettings(); sender.sendMessage("Mortar configuration reloaded. Airborne shells keep their launch settings."); return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("Mortar " + getPluginMeta().getVersion() + ": " + shots.size() + " shells, " + effects.size()
                    + " effects, " + tickets.size() + " shared chunk tickets, " + lights.size() + " temporary lights.");
            sender.sendMessage("Gravity " + settings.gravity + ", apex clearance " + settings.clearance + ", cooldown " + settings.cooldown / 20d
                    + "s, block damage " + settings.blockDamage + ". No flight range/lifetime limit."); return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cooldown")) {
            double seconds;
            try { seconds = Double.parseDouble(args[1]); } catch (NumberFormatException ignored) { seconds = -1; }
            if (!Double.isFinite(seconds) || seconds < .05 || seconds > 60) { sender.sendMessage("Cooldown must be 0.05–60 seconds."); return true; }
            getConfig().set("cooldown-ticks", (int) Math.round(seconds * 20)); saveConfig(); settings = readSettings();
            sender.sendMessage("Mortar cooldown saved: " + settings.cooldown / 20d + " seconds."); return true;
        }
        if (args.length == 0 && sender instanceof Player player) return give(sender, player, null, 1);
        if ((args.length == 2 || args.length == 3) && args[0].equalsIgnoreCase("give"))
            return give(sender, Bukkit.getPlayerExact(args[1]), null, args.length == 3 ? amount(args[2]) : 1);
        if ((args.length == 3 || args.length == 4) && args[0].equalsIgnoreCase("ammo")) {
            Munition type = Munition.parse(args[2]);
            if (type == null) { sender.sendMessage("Unknown munition. Use tab completion for shell IDs."); return true; }
            return give(sender, Bukkit.getPlayerExact(args[1]), type, args.length == 4 ? amount(args[3]) : 1);
        }
        return false;
    }
    private int amount(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; } }
    private boolean give(CommandSender sender, Player target, Munition type, int count) {
        if (target == null) { sender.sendMessage("The player must be online."); return true; }
        if (count < 1 || count > (type == null ? 16 : 64)) { sender.sendMessage("Amount must be 1–" + (type == null ? 16 : 64) + "."); return true; }
        ItemStack sample = item(type, 1); int capacity = 0;
        for (ItemStack stack : target.getInventory().getStorageContents())
            if (stack == null || stack.getType().isAir()) capacity += sample.getMaxStackSize();
            else if (stack.isSimilar(sample)) capacity += Math.max(0, sample.getMaxStackSize() - stack.getAmount());
        if (capacity < count) { sender.sendMessage("Not enough inventory space; no items were given."); return true; }
        if (type == null) for (int i = 0; i < count; i++) target.getInventory().addItem(item(null, 1));
        else target.getInventory().addItem(item(type, count));
        sender.sendMessage("Gave " + count + " " + (type == null ? "Mortar" : type.title) + " to " + target.getName() + "."); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mortar.admin")) return List.of();
        List<String> choices = args.length == 1 ? List.of("give", "ammo", "cooldown", "reload", "status")
                : args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("ammo"))
                ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                : args.length == 3 && args[0].equalsIgnoreCase("ammo") ? Arrays.stream(Munition.values()).map(m -> m.id).toList()
                : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(c -> c.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private record ChunkKey(UUID world, int x, int z) {}
    private static final class TemporaryLight {
        final BlockData original, placed;
        int references = 1;
        TemporaryLight(BlockData original, BlockData placed) { this.original = original; this.placed = placed; }
    }
    private record Payload(Munition type, float power, double depth, double height, List<Double> depths, List<Double> powers,
                           int interval, int count, double radius, int duration, double groundSearch,
                           int lightLevel, int lightHeight, int lightSpacing, int smokeDensity,
                           double smokeHeight, double smokeDrift, int fireRadius, int terrainRadius) {}
    private record Settings(double gravity, int cooldown, double clearance, double targetDistance, int maxShots,
                            int maxEffects, int ammoPerShot, boolean blockDamage, boolean fire, EnumMap<Munition, Payload> payloads) {}
}
