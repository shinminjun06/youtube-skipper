package com.pivotry.blockauto.core;

/**
 * Per-held-stroke online response calibration.
 *
 * In Block Blast Classic the displayed full-size block can move ~1.5 screen
 * pixels per input finger pixel. The old position servo assumed 1:1, causing
 * the v1.4.1 diagnostics (8) to overshoot from x322 to x667 while the desired
 * x was 600, partly clipping the 1x5 block off the right edge.
 *
 * Distinct from DragCalibration: these gains come from a confirmed full-shape
 * MOVEMENT probe (not a successful placement) and exist for this held stroke
 * only. They never grant permission to release a piece. Always reobserve.
 */
public final class DragResponse {
    // A cautious pre-probe estimate based on repeated 1.4-1.5x movement in
    // uploaded Android diagnostics; never treated as verified calibration.
    private static final float SAFE_PRIOR=1.45f;
    private float gainX=SAFE_PRIOR,gainY=SAFE_PRIOR;
    private boolean measured;
    private boolean yMeasured;
    public void reset(){gainX=SAFE_PRIOR;gainY=SAFE_PRIOR;measured=false;yMeasured=false;}
    public float gainX(){return gainX;}
    public float gainY(){return gainY;}
    public boolean measured(){return measured;}

    /**
     * A uniquely identified, direction-consistent move provides a local
     * gain observation. The first horizontal deliberate probe establishes a
     * provisional isotropic gain; later vertical evidence may refine Y.
     */
    public boolean observe(float commandX,float commandY,float actualX,float actualY,float cell){
        if(!finite(commandX)||!finite(commandY)||!finite(actualX)||!finite(actualY)||
           !finite(cell)||cell<=10f)return false;
        float dx=Math.abs(commandX),dy=Math.abs(commandY);
        if(Math.max(dx,dy)<cell*.25f)return false;
        // Each moved axis is learned independently. A diagonal movement
        // is valid evidence for both axes; an orthogonal jump when that
        // orthogonal axis was NOT commanded is contradictory and rejected.
        boolean usableX=dx>=cell*.25f,usableY=dy>=cell*.25f;
        if(!usableX && Math.abs(actualX)>cell*.38f)return false;
        if(!usableY && Math.abs(actualY)>cell*.38f)return false;
        float measuredX=0f,measuredY=0f;
        if(usableX) {
            if(commandX*actualX<=0f)return false;
            measuredX=actualX/commandX;
            if(measuredX<.68f||measuredX>2.30f)return false;
        }
        if(usableY) {
            if(commandY*actualY<=0f)return false;
            measuredY=actualY/commandY;
            if(measuredY<.68f||measuredY>2.30f)return false;
        }
        if(!usableX && !usableY)return false;
        if(usableX)gainX=measured?(.65f*gainX+.35f*measuredX):measuredX;
        if(usableY)gainY=yMeasured?(.65f*gainY+.35f*measuredY):measuredY;
        if(usableY)yMeasured=true;
        if(!measured){
            if(!usableX)gainX=gainY;
            if(!usableY)gainY=gainX;
        }
        measured=true;
        return true;
    }

    /**
     * Image-error to pointer step, compensating for the measured game gain.
     * Damp and cap the command, but also GUARANTEE that the predicted visible
     * rectangle cannot be driven outside the capture frame by this step.
     * The last assumption is conservative; the controller re-observes every
     * input before any subsequent touch or release.
     */
    public float[] correction(float errorX,float errorY,float originX,float originY,
            int pieceWidth,int pieceHeight,float cell,int width,int height){
        if(!finite(errorX)||!finite(errorY)||!finite(originX)||!finite(originY)||
           !finite(cell)||cell<=10f||pieceWidth<1||pieceHeight<1||
           pieceWidth>8||pieceHeight>8||width<1||height<1)
            throw new IllegalArgumentException("Invalid visual-servo geometry");
        float sx=axis(errorX,cell,gainX);
        float sy=axis(errorY,cell,gainY);
        final float pad=Math.max(2f,cell*.04f);
        // Prevent any command likely to hide a cell beyond either screen edge.
        float right=width-pieceWidth*cell-pad;
        float bottom=height-pieceHeight*cell-pad;
        // A pixel-tracked origin can sit just outside the strict inset when
        // the sprite is at the edge. Do not abort for a tiny, uniquely tracked
        // overhang if the requested correction explicitly returns it INWARD.
        // Larger clipping or any outward correction remains a hard stop.
        // Importantly, this never authorizes release: ALIGN still requires
        // stable target-cell preview, followed by MoveVerifier after release.
        final float smallOverhang=Math.min(16f,cell*.18f);
        if(originX<pad-smallOverhang || originX>right+smallOverhang ||
           originY<pad-smallOverhang || originY>bottom+smallOverhang)
            throw new IllegalArgumentException("Sprite exceeds recoverable screen margin");
        if((originX<pad && errorX<=0) || (originX>right && errorX>=0) ||
           (originY<pad && errorY<=0) || (originY>bottom && errorY>=0))
            throw new IllegalArgumentException("Correction is not directed back onscreen");
        sx=limit(sx,(pad-originX)/gainX,(right-originX)/gainX);
        sy=limit(sy,(pad-originY)/gainY,(bottom-originY)/gainY);
        // Strict numeric clamp alone cannot bring an already marginally
        // overhanging sprite back inside. Force a minimum inward step, then
        // re-observe the entire shape before considering ANY further action.
        if(originX<pad) sx=Math.max(sx,(pad+.1f-originX)/gainX);
        else if(originX>right) sx=Math.min(sx,(right-.1f-originX)/gainX);
        if(originY<pad) sy=Math.max(sy,(pad+.1f-originY)/gainY);
        else if(originY>bottom) sy=Math.min(sy,(bottom-.1f-originY)/gainY);
        return new float[]{sx,sy};
    }
    private static float axis(float error,float cell,float gain){
        float magnitude=Math.abs(error);
        float ratio=magnitude<cell*.9f?.72f:.92f;
        float cap=magnitude<cell*.9f?cell*.8f:cell*3f;
        return limit((error/gain)*ratio,-cap,cap);
    }
    private static float limit(float x,float lo,float hi){
        return Math.max(lo,Math.min(hi,x));
    }
    private static boolean finite(float x){return !Float.isNaN(x)&&!Float.isInfinite(x);}
}
