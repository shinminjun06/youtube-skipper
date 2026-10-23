package com.pivotry.blockauto.core;

import java.util.*;

/** Immutable values crossing the image, planner and Android control boundaries. */
public final class Game {
    private Game() {}
    public static final class Image {
        public final int width,height;
        public final int[] pixels;
        public Image(int w,int h,int[] p) {
            if(w<1 || h<1 || p.length!=w*h) throw new IllegalArgumentException("image size");
            width=w; height=h; pixels=p;
        }
        public int at(float x,float y) { return pixels[Math.max(0,Math.min(height-1,(int)y))*width+Math.max(0,Math.min(width-1,(int)x))]; }
    }
    public static final class Piece {
        public final int[] xs,ys;
        public final int w,h;
        public final String key;
        public Piece(int... coordinates) {
            if(coordinates.length<2 || coordinates.length%2!=0) throw new IllegalArgumentException("piece");
            TreeSet<Integer> cells=new TreeSet<>();
            int minX=8,minY=8;
            for(int i=0;i<coordinates.length;i+=2) {minX=Math.min(minX,coordinates[i]);minY=Math.min(minY,coordinates[i+1]);}
            for(int i=0;i<coordinates.length;i+=2) {
                int x=coordinates[i]-minX,y=coordinates[i+1]-minY;
                if(x<0||y<0||x>7||y>7) throw new IllegalArgumentException("piece bounds");
                cells.add(y*8+x);
            }
            xs=new int[cells.size()];ys=new int[cells.size()];
            int i=0,mx=0,my=0;StringBuilder k=new StringBuilder();
            for(int c:cells) {xs[i]=c%8;ys[i]=c/8;mx=Math.max(mx,xs[i]);my=Math.max(my,ys[i]);k.append(xs[i]).append(',').append(ys[i]).append(';');i++;}
            w=mx+1;h=my+1;key=k.toString();
        }
        public long mask(int x,int y) {
            if(x<0||y<0||x+w>8||y+h>8) throw new IllegalArgumentException("placement bounds");
            long b=0;for(int i=0;i<xs.length;i++) b|=1L<<((y+ys[i])*8+x+xs[i]);return b;
        }
    }
    public static final class Shape {
        public final int slot,color;
        public final Piece piece;
        public final float pickX,pickY;
        public Shape(int slot,Piece p,float x,float y,int color) {this.slot=slot;piece=p;pickX=x;pickY=y;this.color=color;}
    }
    public static final class Frame {
        public final long board;
        public final List<Shape> shapes;
        public final float left,top,cell,confidence;
        public final int width,height;
        public Frame(long board,List<Shape> shapes,float left,float top,float cell,int w,int h,float confidence) {
            this.board=board;this.shapes=Collections.unmodifiableList(new ArrayList<>(shapes));this.left=left;this.top=top;this.cell=cell;this.width=w;this.height=h;this.confidence=confidence;
        }
        public String signature() {StringBuilder s=new StringBuilder(Long.toHexString(board));for(Shape p:shapes)s.append('|').append(p.slot).append(':').append(p.piece.key);return s.toString();}
    }
}
