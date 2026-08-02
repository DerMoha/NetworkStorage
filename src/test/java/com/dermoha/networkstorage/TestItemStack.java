package com.dermoha.networkstorage;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Lightweight ItemStack for Paper API tests that do not start a server registry. */
public final class TestItemStack extends ItemStack {
    private final Material type;
    private int amount;

    public TestItemStack(Material type, int amount) {
        super();
        this.type = type;
        this.amount = amount;
    }

    @Override
    public Material getType() {
        return type;
    }

    @Override
    public int getAmount() {
        return amount;
    }

    @Override
    public void setAmount(int amount) {
        this.amount = amount;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public ItemStack clone() {
        return new TestItemStack(type, amount);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemStack item
                && type == item.getType()
                && amount == item.getAmount();
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(type) + amount;
    }
}
