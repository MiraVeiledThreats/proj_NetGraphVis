package netgraph.borders;

/**
 * Immutable world-coordinate rectangle enclosing devices that belong to one
 * NIC/CIDR network, or to the undetermined-network grouping.
 */
public final class NetworkBorder {
    private final int networkId;
    private final boolean undetermined;
    private final String label;

    private final float minX;
    private final float minY;
    private final float maxX;
    private final float maxY;

    private final int memberCount;

    public NetworkBorder(
            final int networkId,
            final boolean undetermined,
            final String label,
            final float minX,
            final float minY,
            final float maxX,
            final float maxY,
            final int memberCount) {

        this.networkId = networkId;
        this.undetermined = undetermined;
        this.label = label == null ? "" : label;

        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;

        this.memberCount = memberCount;
    }

    public int getNetworkId() {
        return networkId;
    }

    public boolean isUndetermined() {
        return undetermined;
    }

    public String getLabel() {
        return label;
    }

    public float getMinX() {
        return minX;
    }

    public float getMinY() {
        return minY;
    }

    public float getMaxX() {
        return maxX;
    }

    public float getMaxY() {
        return maxY;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public boolean containsBorder(
            final double x,
            final double y,
            final double tolerance) {

        final boolean nearLeft =
                Math.abs(x - minX) <= tolerance
                        && y >= minY - tolerance
                        && y <= maxY + tolerance;

        final boolean nearRight =
                Math.abs(x - maxX) <= tolerance
                        && y >= minY - tolerance
                        && y <= maxY + tolerance;

        final boolean nearTop =
                Math.abs(y - minY) <= tolerance
                        && x >= minX - tolerance
                        && x <= maxX + tolerance;

        final boolean nearBottom =
                Math.abs(y - maxY) <= tolerance
                        && x >= minX - tolerance
                        && x <= maxX + tolerance;

        return nearLeft || nearRight || nearTop || nearBottom;
    }
}