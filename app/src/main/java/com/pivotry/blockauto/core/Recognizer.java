package com.pivotry.blockauto.core;

import java.util.*;

/** Image-evidence grid and tray detector. All coordinates are captured display pixels. */
public final class Recognizer {
    private Recognizer() {}
    static int r(int c){return c>>16&255;}
    static int g(int c){return c>>8&255;}
    static int b(int c){return c&255;}
    static int hi(int c){return Math.max(r(c),Math.max(g(c),b(c)));}
    static float light(int c){return .299f*r(c)+.587f*g(c)+.114f*b(c);}
    static int distance(int a,int c){return Math.abs(r(a)-r(c))+Math.abs(g(a)-g(c))+Math.abs(b(a)-b(c));}
    public static boolean isTile(int c){return hi(c)>150 && hi(c)-Math.min(r(c),Math.min(g(c),b(c)))>40;}
    private static float line(Game.Image im,float x,float y,boolean vertical) {
        float v=255;
        for(int d=-2;d<=2;d++)v=Math.min(v,light(im.at(x+(vertical?d:0),y+(vertical?0:d))));
        return v;
    }
    private static float gridScore(Game.Image im,float l,float t,float p) {
        if(t+8*p>im.height*.82f || p<im.width*.07f) return -1;
        float sum=0;int agrees=0;
        for(int i=1;i<8;i++)for(int j=0;j<8;j++) {
            float x=l+i*p,y=t+(j+.5f)*p;
            float contrast=(light(im.at(x-p*.22f,y))+light(im.at(x+p*.22f,y)))*.5f-line(im,x,y,true);
            sum+=Math.min(35,Math.max(-20,contrast));if(contrast>3)agrees++;
            x=l+(j+.5f)*p;y=t+i*p;
            contrast=(light(im.at(x,y-p*.22f))+light(im.at(x,y+p*.22f)))*.5f-line(im,x,y,false);
            sum+=Math.min(35,Math.max(-20,contrast));if(contrast>3)agrees++;
        }
        float border=0;
        for(int j=0;j<8;j++) {
            float x=l+(j+.5f)*p,y=t+8*p;
            float bottom=light(im.at(x,y-p*.2f))-line(im,x,y,false);
            float top=light(im.at(x,t+p*.2f))-line(im,x,t,false);
            if(bottom<3||top<3)return -1;
            border+=Math.min(35,bottom)+Math.min(35,top);
        }
        return agrees<85?-1:sum/112f+border/64f;
    }
    public static Game.Frame read(Game.Image im) {
        if(im.width<300 || im.height<im.width*1.15f) return null;
        int step=Math.max(2,im.width/350);
        float best=5,l=0,t=0,p=0;
        for(int y=(int)(im.height*.13f);y<im.height*.56f;y+=step) {
            for(int threshold:new int[]{65,105,145,185}) {
                int start=-1;
                for(int x=0;x<im.width;x++) {
                    if(light(im.at(x,y))<threshold) {if(start<0)start=x;}
                    else if(start>=0) {
                        int size=x-start;
                        if(start>im.width*.015 && start<im.width*.2 && size>im.width*.65 && size<im.width*.97) {
                            float pitch=size/8f,score=gridScore(im,start,y,pitch);
                            if(score>best){best=score;l=start;t=y;p=pitch;}
                        }
                        start=-1;
                    }
                }
            }
        }
        if(p==0)return null;
        long board=0;
        int baseline=0;float darkest=Float.MAX_VALUE;
        for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
            int c=im.at(l+(x+.5f)*p,t+(y+.5f)*p);
            if(light(c)<darkest){darkest=light(c);baseline=c;}
        }
        for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
            int hit=0;
            for(float dy:new float[]{-.18f,0,.18f})for(float dx:new float[]{-.18f,0,.18f}) {
                int c=im.at(l+(x+.5f+dx)*p,t+(y+.5f+dy)*p);
                if(isTile(c)&&distance(c,baseline)>85)hit++;
            }
            if(hit>=7)board|=1L<<(y*8+x);
            else if(hit>1)return null; // animation or partially covered cell
        }
        List<Game.Shape> shapes=new ArrayList<>();
        for(int slot=0;slot<3;slot++) {
            Game.Shape s=tray(im,l,t,p,slot);
            if(s!=null)shapes.add(s);
        }
        return new Game.Frame(board,shapes,l,t,p,im.width,im.height,Math.min(1,best/16));
    }
    private static Game.Shape tray(Game.Image im,float l,float t,float p,int slot) {
        int x1=(int)(l+slot*8*p/3),x2=(int)(l+(slot+1)*8*p/3);
        int y1=(int)(t+8.7f*p),y2=Math.min(im.height-1,(int)(t+11.9f*p));
        if(y2<=y1)return null;
        Map<Integer,Integer> colors=new HashMap<>();
        for(int y=y1;y<y2;y+=4)for(int x=x1;x<x2;x+=4) {
            int c=im.at(x,y),key=((r(c)/16)<<8)|((g(c)/16)<<4)|(b(c)/16);
            colors.put(key,colors.getOrDefault(key,0)+1);
        }
        int bg=colors.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        bg=((bg>>8&15)*16+8)<<16 | ((bg>>4&15)*16+8)<<8 | (bg&15)*16+8;
        int rw=x2-x1,rh=y2-y1;
        boolean[] mask=new boolean[rw*rh];
        int minX=rw,minY=rh,maxX=-1,maxY=-1;
        for(int y=0;y<rh;y++)for(int x=0;x<rw;x++) {
            int c=im.at(x1+x,y1+y);
            if(isTile(c)&&distance(c,bg)>100) {
                mask[y*rw+x]=true;minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
            }
        }
        if(maxX<minX)return null;
        // Separate beveled square faces. Their measured sizes set the lattice pitch;
        // a tiny gap does not add a phantom row or column.
        List<Integer> sizes=new ArrayList<>();
        boolean[] visited=new boolean[mask.length];int[] queue=new int[mask.length];
        for(int i=0;i<mask.length;i++)if(mask[i]&&!visited[i]) {
            int head=0,tail=0,ax=rw,ay=rh,bx=0,by=0;queue[tail++]=i;visited[i]=true;
            while(head<tail) {
                int a=queue[head++],xx=a%rw,yy=a/rw;
                ax=Math.min(ax,xx);ay=Math.min(ay,yy);bx=Math.max(bx,xx);by=Math.max(by,yy);
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                    int nx=xx+dx,ny=yy+dy;
                    if(nx>=0&&ny>=0&&nx<rw&&ny<rh) {int n=ny*rw+nx;if(mask[n]&&!visited[n]){visited[n]=true;queue[tail++]=n;}}
                }
            }
            if(tail>p*p*.025 && bx-ax<p*.68 && by-ay<p*.68) {sizes.add(bx-ax+1);sizes.add(by-ay+1);}
        }
        if(sizes.isEmpty())return null;
        Collections.sort(sizes);float unit=sizes.get(sizes.size()/2)+1;
        if(unit<p*.26 || unit>p*.68)return null;
        int cols=Math.round((maxX-minX+1)/unit),rows=Math.round((maxY-minY+1)/unit);
        if(cols<1||cols>5||rows<1||rows>5)return null;
        float ux=(maxX-minX+1)/(float)cols,uy=(maxY-minY+1)/(float)rows;
        if(Math.abs(ux-uy)>unit*.2)return null;
        List<Integer> coords=new ArrayList<>();float pickX=0,pickY=0,near=Float.MAX_VALUE;int color=0;
        for(int y=0;y<rows;y++)for(int x=0;x<cols;x++) {
            int hits=0;
            for(float dy:new float[]{-.2f,0,.2f})for(float dx:new float[]{-.2f,0,.2f}) {
                int sx=(int)(minX+(x+.5f+dx)*ux),sy=(int)(minY+(y+.5f+dy)*uy);
                if(mask[sy*rw+sx])hits++;
            }
            if(hits>=7) {
                coords.add(x);coords.add(y);
                float d=(x-(cols-1)*.5f)*(x-(cols-1)*.5f)+(y-(rows-1)*.5f)*(y-(rows-1)*.5f);
                if(d<near){near=d;pickX=x1+minX+(x+.5f)*ux;pickY=y1+minY+(y+.5f)*uy;color=im.at(pickX,pickY);}
            } else if(hits>1)return null;
        }
        if(coords.isEmpty())return null;
        int[] cells=coords.stream().mapToInt(i->i).toArray();
        Game.Piece piece=new Game.Piece(cells);
        // Reject disconnected decoration, but accept diagonal-connected game pieces.
        boolean[] reach=new boolean[piece.xs.length];reach[0]=true;int count=1;
        for(int k=0;k<piece.xs.length;k++)for(int a=0;a<reach.length;a++)if(reach[a])for(int b=0;b<reach.length;b++)
            if(!reach[b]&&Math.abs(piece.xs[a]-piece.xs[b])<=1&&Math.abs(piece.ys[a]-piece.ys[b])<=1){reach[b]=true;count++;}
        if(count!=reach.length)return null;
        return new Game.Shape(slot,piece,pickX,pickY,color);
    }
}
