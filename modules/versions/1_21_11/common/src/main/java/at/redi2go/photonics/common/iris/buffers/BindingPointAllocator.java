package at.redi2go.photonics.common.iris.buffers;

import it.unimi.dsi.fastutil.ints.IntSet;

final class BindingPointAllocator {
    private BindingPointAllocator() {
    }

    static int[] allocate(
            int maximumBindingPoints,
            IntSet occupiedBindingPoints,
            int requestedBindingPoints,
            String bindingType
    ) {
        if (maximumBindingPoints < 0)
            throw new IllegalArgumentException("maximumBindingPoints must not be negative");
        if (requestedBindingPoints < 0)
            throw new IllegalArgumentException("requestedBindingPoints must not be negative");

        int[] result = new int[requestedBindingPoints];
        int allocated = 0;
        int free = 0;

        for (int bindingPoint = maximumBindingPoints - 1; bindingPoint >= 0; bindingPoint--) {
            if (occupiedBindingPoints.contains(bindingPoint)) continue;

            free++;
            if (allocated < requestedBindingPoints)
                result[allocated++] = bindingPoint;
        }

        if (allocated != requestedBindingPoints) {
            throw new IllegalStateException(
                    "Not enough " + bindingType + " for Photonics: requested "
                            + requestedBindingPoints + ", but only " + free
                            + " free within device limit " + maximumBindingPoints
            );
        }

        return result;
    }
}
