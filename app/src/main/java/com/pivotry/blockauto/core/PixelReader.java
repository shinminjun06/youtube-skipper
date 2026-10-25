package com.pivotry.blockauto.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Convert MediaProjection RGBA into ARGB, preserving the original sampling. */
public final class PixelReader {
    private PixelReader() {}

    /**
     * RGBA images arrive with the same physical size and row padding on
     * nearly every frame. Store only sampling OFFSETS, never image pixels.
     * Recreate the map if the screen geometry or stride changes.
     */
    private static final class Sampling {
        final int width,height,stride,maxWidth,w,h;
        final int[] xOffsets,rowOffsets;
        Sampling(int width,int height,int stride,int maxWidth) {
            this.width=width;this.height=height;this.stride=stride;this.maxWidth=maxWidth;
            w=Math.min(width,maxWidth);
            h=Math.max(1,Math.round(height*w/(float)width));
            if((long)w*h>Integer.MAX_VALUE)throw new IllegalArgumentException("Image dimensions");
            xOffsets=new int[w];rowOffsets=new int[h];
            // Keep the EXACT v1.4.5 nearest-neighbor coordinate expression.
            // Changing rounding can shift grid-cell samples near boundaries.
            for(int x=0;x<w;x++) {
                int sx=Math.min(width-1,(int)((x+.5)*width/w));
                xOffsets[x]=sx*4;
            }
            for(int y=0;y<h;y++) {
                int sy=Math.min(height-1,(int)((y+.5)*height/h));
                rowOffsets[y]=sy*stride;
            }
        }
        boolean matches(int wi,int he,int st,int mw){
            return width==wi&&height==he&&stride==st&&maxWidth==mw;
        }
    }
    private static volatile Sampling sampling;

    public static Game.Image read(ByteBuffer rgba,int width,int height,int rowStride,int pixelStride,int maxWidth){
        if(rgba==null||width<1||height<1||maxWidth<1||pixelStride!=4||
            rowStride<(long)width*4||
            rgba.limit()<(long)(height-1)*rowStride+(long)width*4)
            throw new IllegalArgumentException("RGBA buffer size");
        Sampling map=sampling;
        if(map==null||!map.matches(width,height,rowStride,maxWidth)){
            map=new Sampling(width,height,rowStride,maxWidth);
            sampling=map;
        }
        int[] out=new int[map.w*map.h];
        ByteBuffer bytes=rgba.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for(int y=0;y<map.h;y++){
            int row=map.rowOffsets[y],dest=y*map.w;
            for(int x=0;x<map.w;x++){
                int c=bytes.getInt(row+map.xOffsets[x]);
                out[dest+x]=(c&0xff00ff00)|((c&255)<<16)|((c>>>16)&255);
            }
        }
        return new Game.Image(map.w,map.h,out);
    }
}
