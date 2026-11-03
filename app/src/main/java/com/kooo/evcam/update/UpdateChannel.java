package com.kooo.evcam.update;

/** Widest release channel the owner has selected; unknown settings fall back to stable. */
public enum UpdateChannel {
    STABLE("stable"), BETA("beta"), ALPHA("alpha");

    public final String value;
    UpdateChannel(String value) { this.value = value; }

    public static UpdateChannel fromValue(String value) {
        for (UpdateChannel channel : values()) if (channel.value.equals(value)) return channel;
        return STABLE;
    }
}
