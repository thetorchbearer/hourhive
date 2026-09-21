package com.hourhive.domain;

public enum Role {
    USER, MODERATOR, ADMIN;

    public boolean atLeast(Role other) {
        return ordinal() >= other.ordinal();
    }
}
