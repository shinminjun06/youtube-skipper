package com.pivotry.blockauto.core;

/**
 * Verified-only online calibration for the Android held-piece touch offset.
 *
 * A vendor/game may draw the dragged piece several cells ABOVE its finger.
 * Never infer that offset from a pickup screenshot alone; call recordVerified
 * only after the game board AND consumed tray slot match MoveVerifier.
 *
 * Does NOT inject touches or authorize release. Android always re-observes
 * the requested cells before lifting its finger.
 */
public final class DragCalibration {
    public static final class Observation {
        public final int slot,width,height;
        public final float cell,fingerX,fingerY,pieceX,pieceY;
        public Observation(int slot,int width,int height,float cell,
                float fingerX,float fingerY,float pieceX,float pieceY) {
            this.slot=slot;this.width=width;this.height=height;this.cell=cell;
            this.fingerX=fingerX;this.fingerY=fingerY;
            this.pieceX=pieceX;this.pieceY=pieceY;
        }
    }
    private static final class Estimate {
        float normalizedX,normalizedY,cell;
        int agreements;
    }
    private final Estimate[] slots=new Estimate[]{new Estimate(),new Estimate(),new Estimate()};
    private int confirmedSamples=0;
    public void reset(){
        for(Estimate e:slots){e.agreements=0;e.cell=0;e.normalizedX=0;e.normalizedY=0;}
        confirmedSamples=0;
    }
    public int confirmedSamples(){return confirmedSamples;}
    public int confidence(int slot){
        return slot>=0&&slot<slots.length?slots[slot].agreements:0;
    }
    /** Invalid samples can never poison a previously valid learned offset. */
    public boolean recordVerified(Observation s){
        if(s==null||s.slot<0||s.slot>=slots.length||s.width<1||s.width>8||
           s.height<1||s.height>8||!finite(s.cell)||s.cell<=10||
           !finite(s.fingerX)||!finite(s.fingerY)||
           !finite(s.pieceX)||!finite(s.pieceY))return false;
        // Shape-normalized difference between finger point and sprite origin.
        final float x=(s.fingerX-s.pieceX)/s.cell-s.width*.5f;
        final float y=(s.fingerY-s.pieceY)/s.cell-s.height*.5f;
        if(!finite(x)||!finite(y)||Math.abs(x)>12f||Math.abs(y)>12f)return false;
        Estimate e=slots[s.slot];
        if(e.agreements==0||Math.abs(e.cell-s.cell)>s.cell*.04f ||
            Math.abs(e.normalizedX-x)>.33f||Math.abs(e.normalizedY-y)>.33f) {
            // A changed device geometry, drag lift policy or outlying match
            // clears readiness. Two agreeing actual successes are required
            // again before direct placement becomes eligible.
            e.normalizedX=x;e.normalizedY=y;e.cell=s.cell;e.agreements=1;
        }else{
            float alpha=1f/Math.min(e.agreements+1,5);
            e.normalizedX+=alpha*(x-e.normalizedX);
            e.normalizedY+=alpha*(y-e.normalizedY);
            e.agreements=Math.min(8,e.agreements+1);
        }
        confirmedSamples++;
        return true;
    }
    /**
     * Returns null until two actual placements by the same tray slot give
     * agreeing offsets. Null also enforces screen-safe pointer geometry.
     */
    public float[] predict(int slot,Game.Piece piece,float cell,
            float desiredPieceX,float desiredPieceY,int width,int height) {
        if(slot<0||slot>=slots.length||piece==null||!finite(cell)||cell<=10 ||
           width<=0||height<=0||!finite(desiredPieceX)||!finite(desiredPieceY))return null;
        Estimate e=slots[slot];
        if(e.agreements<2||Math.abs(e.cell-cell)>cell*.04f)return null;
        float px=desiredPieceX+(piece.w*.5f+e.normalizedX)*cell;
        float py=desiredPieceY+(piece.h*.5f+e.normalizedY)*cell;
        float edge=Math.max(4,cell*.10f);
        if(!finite(px)||!finite(py)||px<edge||py<edge||
           px>width-edge||py>height-edge)return null;
        return new float[]{px,py};
    }
    /**
     * Faster bounded position servo: far steps are about 88% of measured
     * error with a three-cell cap; near a cell they slow to avoid snap jitter.
     * The Android caller ALWAYS measures the sprite again after each step.
     */
    public static float correction(float error,float cell) {
        if(!finite(error)||!finite(cell)||cell<=0)
            throw new IllegalArgumentException("Invalid drag geometry");
        float a=Math.abs(error);
        float ratio=a<cell*.9f?.72f:.88f;
        float cap=a<cell*.9f?cell*.8f:cell*3f;
        return Math.max(-cap,Math.min(cap,error*ratio));
    }
    private static boolean finite(float v){return !Float.isNaN(v)&&!Float.isInfinite(v);}
}
