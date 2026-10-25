package com.pivotry.blockauto.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verified-only calibration for Android held-piece touch offset.
 *
 * A visual drag offset is not transferable between different block shapes,
 * even if they appear in the SAME tray slot. User v1.4.3 diagnostic (9):
 * slot 1 learned multiple times (19 successful moves overall) but a blue
 * vertical 1x4 was directed to x=600,y=688 and rendered at x=645,y=828,
 * clipping the right edge. Never extrapolate a slot-wide calibration across
 * shape geometry; also avoid direct mode when the preview is near capture
 * edges, where a small prediction error would make it untrackable.
 *
 * Call recordVerified ONLY after stable MoveVerifier board + tray success.
 * This class cannot inject touches or authorize a release.
 */
public final class DragCalibration {
    public static final class Observation {
        public final int slot,width,height;
        public final String pieceKey;
        public final float cell,fingerX,fingerY,pieceX,pieceY;
        public Observation(int slot,String pieceKey,int width,int height,float cell,
                float fingerX,float fingerY,float pieceX,float pieceY) {
            this.slot=slot;this.pieceKey=pieceKey;
            this.width=width;this.height=height;this.cell=cell;
            this.fingerX=fingerX;this.fingerY=fingerY;
            this.pieceX=pieceX;this.pieceY=pieceY;
        }
        /** Legacy tests may still create an unkeyed sample. It can never
         * unlock direct mode for a real Game.Piece, which has a real key. */
        @Deprecated public Observation(int slot,int width,int height,float cell,
                float fingerX,float fingerY,float pieceX,float pieceY) {
            this(slot,"UNKEYED",width,height,cell,fingerX,fingerY,pieceX,pieceY);
        }
    }
    /**
     * A stable legal grid preview can authorize a guarded placement, but it
     * never measures the dragged sprite origin. Keep predicted/direct mode
     * learning limited to true strict physical target-local observations.
     * Earlier fallback/proxy sightings invalidate a calibration candidate,
     * even if a later target observation appears trustworthy.
     */
    public static boolean mayRecordVerifiedTargetSample(
            boolean strictPhysicalTargetMatch, boolean usedRecoveryProxy) {
        return strictPhysicalTargetMatch && !usedRecoveryProxy;
    }
    private static final class Estimate {
        float normalizedX,normalizedY,cell;
        int agreements;
    }
    private final LinkedHashMap<String,Estimate> estimates=new LinkedHashMap<>();
    private int confirmedSamples;
    public void reset(){estimates.clear();confirmedSamples=0;}
    public int confirmedSamples(){return confirmedSamples;}
    public int confidence(int slot){
        int result=0;
        String prefix=slot+":";
        for(Map.Entry<String,Estimate> e:estimates.entrySet())
            if(e.getKey().startsWith(prefix))
                result=Math.max(result,e.getValue().agreements);
        return result;
    }
    public int confidence(int slot,String pieceKey){
        Estimate e=estimates.get(key(slot,pieceKey));
        return e==null?0:e.agreements;
    }
    private static String key(int slot,String pieceKey){
        return slot+":"+pieceKey;
    }
    /** Invalid samples can never poison an earlier valid offset. */
    public boolean recordVerified(Observation s){
        if(s==null||s.slot<0||s.slot>2||s.pieceKey==null||
           s.pieceKey.length()<3||s.pieceKey.length()>160||
           s.width<1||s.width>8||s.height<1||s.height>8||
           !finite(s.cell)||s.cell<=10||!finite(s.fingerX)||!finite(s.fingerY)||
           !finite(s.pieceX)||!finite(s.pieceY))return false;
        final float x=(s.fingerX-s.pieceX)/s.cell-s.width*.5f;
        final float y=(s.fingerY-s.pieceY)/s.cell-s.height*.5f;
        if(!finite(x)||!finite(y)||Math.abs(x)>12f||Math.abs(y)>12f)return false;
        String id=key(s.slot,s.pieceKey);
        Estimate e=estimates.get(id);
        if(e==null) {
            // Bound the on-device cache to avoid indefinite session growth.
            if(estimates.size()>=48)estimates.remove(estimates.keySet().iterator().next());
            e=new Estimate();
            estimates.put(id,e);
        }
        if(e.agreements==0||Math.abs(e.cell-s.cell)>s.cell*.04f ||
            Math.abs(e.normalizedX-x)>.33f||Math.abs(e.normalizedY-y)>.33f) {
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
     * Two independent verified successes for the EXACT slot + block shape
     * unlock predictive mode, but never near any CAPTURE boundary. It is
     * especially important to avoid the far-right board column: in the
     * 720px field recording only 40px remains outside that last board cell.
     */
    public float[] predict(int slot,Game.Piece piece,float cell,
            float desiredPieceX,float desiredPieceY,int width,int height) {
        if(slot<0||slot>2||piece==null||!finite(cell)||cell<=10 ||
           width<=0||height<=0||!finite(desiredPieceX)||!finite(desiredPieceY))return null;
        Estimate e=estimates.get(key(slot,piece.key));
        if(e==null||e.agreements<2||Math.abs(e.cell-cell)>cell*.04f)return null;
        // A near-edge prediction error otherwise clips the enlarged piece,
        // so even a unique whole-shape tracker will report NO_HELD_MATCH.
        final float safety=Math.max(10f,cell*.85f);
        if(desiredPieceX<safety||desiredPieceY<safety||
           desiredPieceX+piece.w*cell>width-safety||
           desiredPieceY+piece.h*cell>height-safety)return null;
        float px=desiredPieceX+(piece.w*.5f+e.normalizedX)*cell;
        float py=desiredPieceY+(piece.h*.5f+e.normalizedY)*cell;
        float edge=Math.max(4,cell*.10f);
        if(!finite(px)||!finite(py)||px<edge||py<edge||
           px>width-edge||py>height-edge)return null;
        return new float[]{px,py};
    }
    /** Legacy servo comparison kept for reproducible simulated benchmarks. */
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
