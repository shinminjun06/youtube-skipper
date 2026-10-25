package com.pivotry.blockauto.core;

/**
 * The AccessibilityService's onCompleted() proves only that Android accepted
 * a pointer stroke. It does NOT prove the game grabbed the tray piece.
 * Diagnostic (21): before/probe/held have identical gameplay pixels even
 * though the score animates. Keep pickup proof restricted to board+tray.
 */
public final class PickupControl {
    private PickupControl(){}
    public static float[] stage(Game.Frame frame, Game.Shape shape,int attempt) {
        if(frame==null||shape==null||attempt<0||attempt>1||
           !Float.isFinite(frame.cell)||frame.cell<=10 ||
           !Float.isFinite(shape.pickX)||!Float.isFinite(shape.pickY))
            return null;
        // Move UP out of the tray, not DOWN below it as the old
        // frame.top+8*cell+piece.h*.5*cell+2*cell formula did.
        float endX=Math.max(frame.left+frame.cell*.5f,
            Math.min(frame.left+7.5f*frame.cell,shape.pickX));
        float boardBottom=frame.top+8f*frame.cell;
        float endY=Math.min(shape.pickY-1.4f*frame.cell,
            boardBottom-(attempt==0?1.05f:1.85f)*frame.cell);
        endY=Math.max(frame.top+frame.cell*.8f,endY);
        if(endY>=shape.pickY-frame.cell*.8f ||
           endX<2 || endX>frame.width-2 || endY<2 ||
           endY>frame.height-2)return null;
        return new float[]{endX,endY};
    }
    /** Ignore scoreboard/crown animations entirely. The game board and tray
     * must visibly respond, otherwise the press may have missed or been
     * ignored while the game was transitioning to the next hand. */
    public static boolean gameplayChanged(Game.Image before,Game.Image after,
            Game.Frame frame) {
        if(before==null||after==null||frame==null||
           before.width!=after.width||before.height!=after.height||
           !Float.isFinite(frame.cell)||frame.cell<=10f)return false;
        int x0=Math.max(0,(int)(frame.left-frame.cell*.15f));
        int x1=Math.min(after.width,(int)(frame.left+8.15f*frame.cell));
        int y0=Math.max(0,(int)frame.top);
        int y1=Math.min(after.height,(int)(frame.top+11.9f*frame.cell));
        if(x1<=x0||y1<=y0)return false;
        int step=Math.max(3,(int)(frame.cell*.07f));
        int changed=0;
        for(int y=y0;y<y1;y+=step)for(int x=x0;x<x1;x+=step)
            if(Recognizer.distance(before.pixels[y*before.width+x],
                     after.pixels[y*after.width+x])>40 &&
                    ++changed>=20) return true;
        return false;
    }
}
