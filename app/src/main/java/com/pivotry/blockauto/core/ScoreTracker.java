package com.pivotry.blockauto.core;
import java.util.*;

/** Tracks confirmed manual placements, including a move different from the
 * recommendation. Only a stable, actually consumed piece can advance a chain.
 */
public final class ScoreTracker {
    private int combo,misses;
    private Game.Frame before;

    public int combo(){return combo;}
    public int misses(){return misses;}
    /** The floating COMBO caption is stronger evidence than an inferred chain.
     * Apply only after the caption explicitly includes a parsed number.
     * Disarm any pending move to avoid counting that same clear twice.
     */
    public void observeDisplayedCombo(int visible) {
        if(visible<1 || visible>99999) return;
        combo=visible+1;
        misses=0;
        before=null;
    }
    /** In the examined Android Classic baseline, a displayed N means chain N+1. */
    public void seedDisplayedCombo(int visible) {
        combo=visible>0?Math.min(100000,visible+1):0;
        misses=0;before=null;
    }
    public void reset(){combo=0;misses=0;before=null;}

    /** The highlighted move is advice, not a command; retain the whole hand
     * so a different legal manual placement can be recognized correctly.
     */
    public void expect(Game.Frame frame,int suggestedIndex,int x,int y) {
        if(frame==null||suggestedIndex<0||suggestedIndex>=frame.shapes.size())return;
        if(Rules.place(frame.board,frame.shapes.get(suggestedIndex).piece,x,y)==null)return;
        before=frame;
    }
    public void observe(Game.Frame after) {
        if(after==null||before==null)return;
        Game.Frame old=before;
        if(old.signature().equals(after.signature()))return;
        // A new 3-piece tray is valid only when the prior tray had 1 piece.
        boolean refill=old.shapes.size()==1&&after.shapes.size()==3;
        if(!refill&&old.shapes.size()!=after.shapes.size()+1) {
            invalidate();return;
        }
        if(after.width!=old.width||after.height!=old.height||
            Math.abs(after.left-old.left)>old.cell*.08f ||
            Math.abs(after.top-old.top)>old.cell*.08f ||
            Math.abs(after.cell-old.cell)>old.cell*.02f) {
            invalidate();return;
        }
        Game.Shape placed=null;
        if(refill) placed=old.shapes.get(0);
        else {
            for(Game.Shape shape:old.shapes) {
                Game.Shape matching=null;
                for(Game.Shape remaining:after.shapes)
                    if(remaining.slot==shape.slot) {matching=remaining;break;}
                if(matching==null) {
                    if(placed!=null){invalidate();return;}
                    placed=shape;
                } else if(!matching.piece.key.equals(shape.piece.key)) {
                    invalidate();return;
                }
            }
            for(Game.Shape shape:after.shapes) {
                boolean found=false;
                for(Game.Shape previous:old.shapes)
                    if(previous.slot==shape.slot)found=true;
                if(!found){invalidate();return;}
            }
        }
        if(placed==null){invalidate();return;}
        // The user may ignore the highlighted coordinate. Reconstruct the
        // observed move from the before/after board without guessing where
        // a vanished piece was dropped.
        int lines=-1;
        Game.Piece piece=placed.piece;
        for(int y=0;y<=8-piece.h;y++)for(int x=0;x<=8-piece.w;x++) {
            Rules.Placement p=Rules.place(old.board,piece,x,y);
            if(p==null||p.board!=after.board)continue;
            if(lines>=0&&lines!=p.lines){invalidate();return;}
            lines=p.lines;
        }
        if(lines<0){invalidate();return;}
        if(lines>0){combo++;misses=0;}
        else if(++misses>=3){combo=0;misses=0;}
        before=null;
    }
    private void invalidate(){combo=0;misses=0;before=null;}
}
