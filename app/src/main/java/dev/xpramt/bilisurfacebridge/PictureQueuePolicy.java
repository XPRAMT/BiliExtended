package dev.xpramt.bilisurfacebridge;

/** IJK picture-queue size selection (E4/P): the shipped 12, or 13..16 from `debug.biliextended.pictq`; anything else (unset, 0, negative, above the AVOption maximum of 16) stays 12. */
final class PictureQueuePolicy {
    static final int DEFAULT_SIZE = 12;
    static final int MAX_SIZE = 16;

    private PictureQueuePolicy() {
    }

    static int resolve(int propertyValue) {
        return propertyValue >= DEFAULT_SIZE && propertyValue <= MAX_SIZE ? propertyValue : DEFAULT_SIZE;
    }
}
