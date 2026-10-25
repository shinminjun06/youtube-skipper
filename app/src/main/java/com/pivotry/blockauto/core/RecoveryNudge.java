package com.pivotry.blockauto.core;

/** Safe last-resort reacquisition of a held sprite hidden behind same-color
 * existing board tiles. At most TWO moves while pointer remains held.
 * Never authorizes drop or calibration without independent visual evidence. */
public final class RecoveryNudge {
    private RecoveryNudge(){}
    public static float[] plan(Game.Frame frame,float fingerX,float fingerY,int attempt){
        if(frame==null||!Float.isFinite(fingerX)||!Float.isFinite(fingerY)||
           !Float.isFinite(frame.cell)||frame.cell<=10f||
           frame.width<300||frame.height<500||attempt<0||attempt>1)
           return null;
        float dx=0f,dy=0f,c=frame.cell;
        if(attempt==0)
            dy=(fingerY>frame.top+4.5f*c?-1f:1f)*1.4f*c;
        else
            dx=(fingerX>frame.width*.5f?-1f:1f)*1.25f*c;
        float margin=Math.max(4f,c*.12f);
        if(fingerX+dx<margin||fingerX+dx>frame.width-margin||
           fingerY+dy<margin||fingerY+dy>frame.height-margin)
            return null;
        return new float[]{dx,dy};
    }
}
