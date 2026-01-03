package com.dermoha.networkstorage.storage;

import org.bukkit.inventory.ItemStack;

public class NetworkItemKey {
    private final ItemStack itemStack;
    private final int hashCode;

    public NetworkItemKey(ItemStack item) {
        this.itemStack = item.clone();
        this.itemStack.setAmount(1);

        int hash = itemStack.getType().hashCode();
        if (itemStack.hasItemMeta()) {
            hash = 31 * hash + itemStack.getItemMeta().hashCode();
        }
        this.hashCode = hash;
    }

    public ItemStack getItemStack() {
        return itemStack.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NetworkItemKey that = (NetworkItemKey) o;
        return itemStack.isSimilar(that.itemStack);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
