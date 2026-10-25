package com.pivotry.blockauto.core;

/** Bounded proportional correction of a held pointer toward a visually
 * verified target. Large single-stroke corrections can change a game's
 * finger-to-sprite lift factor and throw the held piece off-screen.
 */
public final class DragStep {
    private DragStep() {}
    public static float axis(float error,float cell) {
        if(!Float.isFinite(error)||!Float.isFinite(cell)||cell<=0)
            throw new IllegalArgumentException("invalid drag geometry");
        final float cap=cell*2.0f;
        // Keep near-target motion gentle to avoid overshooting snap basins.
        float commanded=error*.62f;
        return Math.max(-cap,Math.min(cap,commanded));
    }
}
