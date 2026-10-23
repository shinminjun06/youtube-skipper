package com.pivotry.blockauto.core;
public final class DragAlignment {
    private DragAlignment() {}
    private static final float[] SAMPLES={.22f,.5f,.78f};
    private static final int[][] DIRECTIONS={{-1,0},{1,0},{0,-1},{0,1}};
    public static final class Origin {public final float x,y,confidence;Origin(float x,float y,float c){this.x=x;this.y=y;confidence=c;}}
    private static boolean same(int c,int target) {
        return Recognizer.isTile(c)&&Recognizer.hi(c)>Recognizer.hi(target)*.78f&&Recognizer.distance(c,target)<125;
    }
    private static float score(Game.Image im,Game.Image before,Game.Piece p,int color,float x,float y,float cell) {
        int hits=0,total=0,novel=0,edges=0,clean=0;
        for(int i=0;i<p.xs.length;i++) {
            float xx=x+p.xs[i]*cell,yy=y+p.ys[i]*cell;
            if(!same(im.at(xx+cell*.5f,yy+cell*.5f),color))return -1;
            for(float dy:SAMPLES)for(float dx:SAMPLES) {
                float sx=xx+dx*cell,sy=yy+dy*cell;int c=im.at(sx,sy);total++;
                if(same(c,color)){hits++;if(Recognizer.distance(c,before.at(sx,sy))>60)novel++;}
            }
            for(int[] d:DIRECTIONS) {
                boolean neighbor=false;
                for(int j=0;j<p.xs.length;j++)if(p.xs[j]==p.xs[i]+d[0]&&p.ys[j]==p.ys[i]+d[1]){neighbor=true;break;}
                if(!neighbor) {
                    edges++;
                    float sx=xx+(.5f+d[0]*.55f)*cell,sy=yy+(.5f+d[1]*.55f)*cell;
                    // Static neighboring board blocks must not count as part of the held shape.
                    if(!same(im.at(sx,sy),color)||Recognizer.distance(im.at(sx,sy),before.at(sx,sy))<45)clean++;
                }
            }
        }
        if(hits<total*.9f||novel<total*.3f)return -1;
        return hits/(float)total*.7f+clean/(float)edges*.3f;
    }
    /** Find a newly rendered, enlarged shape, not a stationary tray piece or old board. */
    public static Origin locate(Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,float fingerX,float fingerY){
        if(im.width!=before.width||im.height!=before.height)return null;
        float cell=frame.cell,step=Math.max(2,cell*.08f);
        float minX=Math.max(0,fingerX-(shape.piece.w+1)*cell),maxX=Math.min(im.width-shape.piece.w*cell,fingerX+cell);
        float minY=Math.max(frame.top-cell,fingerY-(shape.piece.h+4)*cell),maxY=Math.min(im.height-shape.piece.h*cell,fingerY+cell*.5f);
        float best=.89f,bx=0,by=0;
        for(float y=minY;y<=maxY;y+=step)for(float x=minX;x<=maxX;x+=step) {
            float s=score(im,before,shape.piece,shape.color,x,y,cell);
            if(s>best){best=s;bx=x;by=y;}
        }
        if(best<.9f)return null;
        // Average equally good nearby fits to avoid picking one edge of a flat score plateau.
        float sx=0,sy=0;int count=0;
        for(float y=by-step;y<=by+step;y+=Math.max(1,step/4))for(float x=bx-step;x<=bx+step;x+=Math.max(1,step/4))
            if(x>=0&&y>=0&&x+shape.piece.w*cell<=im.width&&y+shape.piece.h*cell<=im.height&&score(im,before,shape.piece,shape.color,x,y,cell)>=best-.001f){sx+=x;sy+=y;count++;}
        return new Origin(count==0?bx:sx/count,count==0?by:sy/count,best);
    }
}
