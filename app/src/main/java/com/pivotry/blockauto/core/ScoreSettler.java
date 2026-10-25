package com.pivotry.blockauto.core;

/**
 * A complete board/tray may already be visible while the game's previous
 * score animation is still transitioning. Avoid starting another drag during
 * that brief period. Ignore light score-glint animation (small changes);
 * fail open after 2s rather than trapping the player on a busy decorative UI.
 */
public final class ScoreSettler {
    private Game.Image previous;
    private long startAt=-1L,lastNumericChange=-1L;
    public void reset(){ previous=null;startAt=-1;lastNumericChange=-1; }
    public boolean ready(Game.Image image,long now) {
        if(image==null||now<0)return false;
        if(startAt<0||previous==null){
            startAt=now;lastNumericChange=now;previous=image;return false;
        }
        if(previous.width!=image.width||previous.height!=image.height){
            reset();startAt=now;lastNumericChange=now;previous=image;return false;
        }
        // Original 720x1560 digit band ~= x110..635, y220..320.
        int x0=Math.max(0,(int)(image.width*.15f));
        int x1=Math.min(image.width,(int)(image.width*.89f));
        int y0=Math.max(0,(int)(image.height*.14f));
        int y1=Math.min(image.height,(int)(image.height*.21f));
        int changes=0;
        for(int y=y0;y<y1;y+=6)for(int x=x0;x<x1;x+=6)
            if(Recognizer.distance(previous.pixels[y*image.width+x],
                    image.pixels[y*image.width+x])>60)changes++;
        previous=image;
        if(changes>=120)lastNumericChange=now;
        return now-lastNumericChange>=650 || now-startAt>=2200;
    }
}
