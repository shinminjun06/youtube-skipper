package com.pivotry.blockauto.core;
import java.util.*;

/** Tracks the actual clearing chain from observed, confirmed board changes.
 * Doesn't infer a +combo from a guide alone or invent the on-screen label.
 */
public final class ScoreTracker {
    private int combo, misses;
    private String signature;
    private long expectedBoard;
    private int expectedShapes;
    private int expectedSlot;
    private int expectedLines;
    private boolean armed;

    public int combo(){return combo;}
    public int misses(){return misses;}
    public void reset(){combo=0;misses=0;signature=null;armed=false;}

    /** Call only after choosing a legal recommendation on the current frame. */
    public void expect(Game.Frame frame, int visiblePiece, int x, int y) {
        if (frame==null||visiblePiece<0||visiblePiece>=frame.shapes.size())return;
        Rules.Placement p=Rules.place(frame.board,frame.shapes.get(visiblePiece).piece,x,y);
        if(p==null)return;
        signature=frame.signature();
        expectedBoard=p.board;
        expectedLines=p.lines;
        expectedShapes=frame.shapes.size();
        expectedSlot=frame.shapes.get(visiblePiece).slot;
        armed=true;
    }
    /** Accept only a board matching the predicted legal placement AND a
     * consumed piece (or a freshly dealt full tray after the last piece).
     * Unexpected manual play invalidates the inferred streak safely.
     */
    public void observe(Game.Frame frame) {
        if(!armed||frame==null)return;
        if(frame.signature().equals(signature))return;
        boolean consumed=false;
        if(frame.shapes.size()==expectedShapes-1) {
            consumed=true;
            for(Game.Shape x:frame.shapes)if(x.slot==expectedSlot)consumed=false;
        } else if(expectedShapes==1&&frame.shapes.size()==3)consumed=true;
        if(consumed && frame.board==expectedBoard){
            if(expectedLines>0){combo++;misses=0;}
            else if(++misses>=3){combo=0;misses=0;}
        }else{
            // Without a confirmed observation there is no trustworthy combo.
            combo=0;misses=0;
        }
        armed=false;
    }
}
