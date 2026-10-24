package com.pivotry.blockauto.core;

/**
 * Removes only the bright guide ink from a MediaProjection frame before
 * the board recognizer sees it. We keep the original frame unchanged.
 * Avoids hiding/showing the overlay every capture (visible flicker).
 */
public final class GuideMask {
    private GuideMask() {}
    private static boolean ink(int c) {
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        return r<75 && g>190 && b>105 && b<230 && g-r>125 && b-r>70 && g-b>22;
    }
    public static Game.Image strip(Game.Image image,float left,float top,float cell,
                                   int referenceWidth,int referenceHeight,int[] coords) {
        if(image==null || coords==null || coords.length==0 || (coords.length&1)!=0 ||
           referenceWidth<=0 || referenceHeight<=0 || cell<=0) return image;
        // The overlay and capture use the same display-derived proportions.
        float sx=image.width/(float)referenceWidth,sy=image.height/(float)referenceHeight;
        int[] fixed=null;
        for(int i=0;i<coords.length;i+=2) {
            float x=(left+coords[i]*cell)*sx;
            float y=(top+coords[i+1]*cell)*sy;
            float w=cell*sx,h=cell*sy;
            // Scan only the known ring footprint, not the entire grid.
            int x0=Math.max(0,(int)(x+w*.09f)-3),x1=Math.min(image.width-1,(int)(x+w*.91f)+3);
            int y0=Math.max(0,(int)(y+h*.09f)-3),y1=Math.min(image.height-1,(int)(y+h*.91f)+3);
            int sampleX=Math.max(0,Math.min(image.width-1,(int)(x+w*.50f)));
            int sampleY=Math.max(0,Math.min(image.height-1,(int)(y+h*.50f)));
            int replacement=image.pixels[sampleY*image.width+sampleX];
            // Do not use a suspected guide-colored center as a replacement.
            if(ink(replacement)) continue;
            for(int yy=y0;yy<=y1;yy++)for(int xx=x0;xx<=x1;xx++) {
                float u=(xx-x)/w,v=(yy-y)/h;
                boolean edge=(u>=.065f&&u<=.935f&&v>=.065f&&v<=.935f) &&
                    ((u<.19f||u>.81f) && v>=.10f&&v<=.90f ||
                     (v<.19f||v>.81f) && u>=.10f&&u<=.90f);
                if(!edge)continue;
                int index=yy*image.width+xx;
                if(ink(image.pixels[index])) {
                    if(fixed==null)fixed=image.pixels.clone();
                    fixed[index]=replacement;
                }
            }
        }
        return fixed==null?image:new Game.Image(image.width,image.height,fixed);
    }
}
