package com.pivotry.blockauto.core;

/**
 * Reuse only an analysis from the exact same immutable Game.Image instance.
 * A new capture is ALWAYS analyzed. This is a worker-thread-only helper.
 * Never use for image transforms, guide overlays or manual-advice mode.
 */
public final class FrameRecognitionCache {
    private Game.Image image;
    private Recognizer.Detection detection;

    public void remember(Game.Image raw,Recognizer.Detection observed){
        if(raw==null||observed==null) {reset();return;}
        image=raw;detection=observed;
    }
    public Recognizer.Detection reuse(Game.Image raw,boolean automatic,
            boolean unmodifiedPixels,boolean isNewCapture) {
        if(!automatic||!unmodifiedPixels||isNewCapture||raw==null||raw!=image)
            return null;
        return detection;
    }
    public void reset(){image=null;detection=null;}
}
