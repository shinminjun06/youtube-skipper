package com.pivotry.blockauto.core;
public final class Rules {
    private Rules() {}
    static final long[] ROWS=new long[8],COLS=new long[8];
    static {for(int i=0;i<8;i++){ROWS[i]=255L<<(8*i);COLS[i]=0x0101010101010101L<<i;}}
    public static final class Placement {public final long board;public final int lines;Placement(long b,int n){board=b;lines=n;}}
    public static Placement place(long board,Game.Piece piece,int x,int y){
        if(x<0||y<0||x+piece.w>8||y+piece.h>8)return null;
        long mask=piece.mask(x,y);if((board&mask)!=0)return null;
        long filled=board|mask,clear=0;int lines=0;
        for(long row:ROWS)if((filled&row)==row){clear|=row;lines++;}
        for(long col:COLS)if((filled&col)==col){clear|=col;lines++;}
        return new Placement(filled&~clear,lines);
    }
}
