package com.github.beemerwt;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

record SpawnerKey(UUID worldId, int x, int y, int z) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpawnerKey(UUID id, int x1, int y1, int z1))) return false;
        return x == x1 && y == y1 && z == z1 && worldId.equals(id);
    }

    @Override
    public @NotNull String toString() {
        return worldId + ":" + x + "," + y + "," + z;
    }
}
