package com.pivotry.blockauto.core;
public final class MoveVerifier {
    private final Game.Frame before;
    private final Game.Shape selected;
    public final long expectedBoard;
    public MoveVerifier(Game.Frame before,int shapeIndex,int x,int y){
        this.before=before;selected=before.shapes.get(shapeIndex);
        Rules.Placement p=Rules.place(before.board,selected.piece,x,y);
        if(p==null)throw new IllegalArgumentException("illegal move");expectedBoard=p.board;
    }
    public boolean matches(Game.Frame after){
        if(after==null||after.width!=before.width||after.height!=before.height||after.board!=expectedBoard||
            Math.abs(after.left-before.left)>before.cell*.08||Math.abs(after.top-before.top)>before.cell*.08||Math.abs(after.cell-before.cell)>before.cell*.02)return false;
        if(before.shapes.size()==1)return after.shapes.size()==3; // wait for fresh hand
        if(after.shapes.size()!=before.shapes.size()-1)return false;
        for(Game.Shape old:before.shapes)if(old.slot!=selected.slot) {
            boolean found=false;
            for(Game.Shape now:after.shapes)if(now.slot==old.slot&&now.piece.key.equals(old.piece.key))found=true;
            if(!found)return false;
        }
        for(Game.Shape now:after.shapes)if(now.slot==selected.slot)return false;
        return true;
    }
    public boolean unchanged(Game.Frame after){return after!=null&&before.signature().equals(after.signature());}
}
