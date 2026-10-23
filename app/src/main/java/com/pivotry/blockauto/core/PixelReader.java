package com.pivotry.blockauto.core;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
public final class PixelReader {
    private PixelReader() {}
    public static Game.Image read(ByteBuffer rgba,int width,int height,int rowStride,int pixelStride,int maxWidth){
        if(width<1||height<1||maxWidth<1||pixelStride!=4||rowStride<(long)width*4||
            rgba.limit()<(long)(height-1)*rowStride+(long)width*4)throw new IllegalArgumentException("RGBA buffer size");
        int w=Math.min(width,maxWidth),h=Math.max(1,Math.round(height*w/(float)width));
        int[] out=new int[w*h];ByteBuffer bytes=rgba.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for(int y=0;y<h;y++) {
            int sy=Math.min(height-1,(int)((y+.5)*height/h));
            for(int x=0;x<w;x++) {
                int sx=Math.min(width-1,(int)((x+.5)*width/w));
                int c=bytes.getInt(sy*rowStride+sx*4);
                out[y*w+x]=(c&0xff00ff00)|((c&255)<<16)|((c>>>16)&255);
            }
        }
        return new Game.Image(w,h,out);
    }
}
