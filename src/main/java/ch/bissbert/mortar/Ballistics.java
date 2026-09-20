package ch.bissbert.mortar;

/** Exact endpoint solution for position += velocity; velocity.y -= gravity. */
public final class Ballistics {
    private Ballistics() {}

    public record Solution(double x, double y, double z, int flightTicks) {}

    public static Solution solve(double dx, double dy, double dz, double gravity, double clearance) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || !Double.isFinite(gravity) || gravity <= 0 || !Double.isFinite(clearance) || clearance <= 0)
            throw new IllegalArgumentException("Finite coordinates and positive gravity/clearance required");
        double apex = Math.max(0, dy) + clearance;
        double duration = Math.sqrt(2 * apex / gravity) + Math.sqrt(2 * (apex - dy) / gravity);
        if (!Double.isFinite(duration) || duration >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("Trajectory exceeds numeric bounds");
        int ticks = Math.max(2, (int) Math.ceil(duration));
        double vy = dy / ticks + gravity * (ticks - 1) / 2;
        return new Solution(dx / ticks, vy, dz / ticks, ticks);
    }
}
