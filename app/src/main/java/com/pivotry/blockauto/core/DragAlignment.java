package com.pivotry.blockauto.core;
import java.util.*;
public final class DragAlignment {
    private DragAlignment() {}
    private static final float[] SAMPLES={.22f,.5f,.78f};
    private static final int[][] DIRECTIONS={{-1,0},{1,0},{0,-1},{0,1}};
    public static final class Origin {public final float x,y,confidence;Origin(float x,float y,float c){this.x=x;this.y=y;confidence=c;}}
    /** The destination is legal and was empty before the move: require new pixels
     * throughout the held piece, so adjacent old tiles cannot masquerade as it. */
    public static Origin locateNear(Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,float x,float y) {
        float radius=frame.cell*.55f;
        return search(im,before,frame,shape,Math.max(0,x-radius),Math.min(im.width-shape.piece.w*frame.cell,x+radius),
            Math.max(0,y-radius),Math.min(im.height-shape.piece.h*frame.cell,y+radius),.82f,false);
    }
    /**
     * Strong evidence that a grid-snapped drag PREVIEW occupies precisely the
     * requested cells, even if its art rectangle has a persistent ~0.2-cell
     * rendering offset. Caller must ALSO confirm a unique, stable target-local
     * origin close to the intended coordinate before issuing ACTION_UP.
     *
     * This is NOT a substitute for verifying the board after release.
     */
    public static boolean targetCellPreview(Game.Image current,Game.Image before,
            Game.Frame frame,Game.Shape held,int column,int row) {
        if(current==null||before==null||frame==null||held==null ||
           current.width!=before.width||current.height!=before.height)return false;
        if(Rules.place(frame.board,held.piece,column,row)==null)return false;
        float cell=frame.cell;
        if(cell<=0)return false;
        int n=held.piece.xs.length;
        if(n==0)return false;
        for(int i=0;i<n;i++) {
            float left=frame.left+(column+held.piece.xs[i])*cell;
            float top=frame.top+(row+held.piece.ys[i])*cell;
            // Four interior probes for EACH requested square. A piece one
            // column/row away cannot satisfy the absent edge-column probes.
            int matched=0;
            for(float oy:new float[]{.39f,.61f})for(float ox:new float[]{.39f,.61f}) {
                int px= Math.round(left+ox*cell), py=Math.round(top+oy*cell);
                if(px<0||py<0||px>=current.width||py>=current.height)continue;
                int now=current.pixels[py*current.width+px];
                int was=before.pixels[py*before.width+px];
                if(same(now,held.color) && Recognizer.distance(now,was)>60)matched++;
            }
            if(matched<4)return false;
        }
        return true;
    }

    private static boolean same(int c,int target) {
        return Recognizer.isTile(c)&&Recognizer.hi(c)>Recognizer.hi(target)*.78f&&Recognizer.distance(c,target)<125;
    }
    private static float score(Game.Image im,Game.Image before,Game.Piece p,int color,float x,float y,float cell,float minNovel,boolean changedCenters) {
        int hits=0,total=0,novel=0,edges=0,clean=0;
        for(int i=0;i<p.xs.length;i++) {
            float xx=x+p.xs[i]*cell,yy=y+p.ys[i]*cell;
            float centerX=xx+cell*.5f,centerY=yy+cell*.5f;
            if(!same(im.at(centerX,centerY),color))return -1;
            if(changedCenters&&Recognizer.distance(im.at(centerX,centerY),before.at(centerX,centerY))<=60)return -1;
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
        // During wide-area tracking the held shape can pass UNDER our floating
        // status panel. On the v1.3.1 field diagnostic the purple 2x2 was
        // visibly present at (523,273), but the panel covered 6/36 sample
        // pixels (83% coverage), causing the old 90% rule to reject it.
        // Keep the stricter 90% requirement for target-area confirmation;
        // the relaxed result below is used only to re-find a moving piece.
        float requiredCoverage=changedCenters?.80f:.90f;
        if(hits<total*requiredCoverage||novel<total*minNovel)return -1;
        if(changedCenters && clean<edges*.75f)return -1;
        return hits/(float)total*.7f+clean/(float)edges*.3f;
    }
    /** A game-specific finger lift may put the held shape over the board.
     * Search the visible height; every cell center must be newly rendered so
     * unchanged same-color board/tray cells cannot complete a false match. */
    public static Origin locate(Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,float fingerX,float fingerY){
        List<Origin> candidates=stagedCandidates(im,before,frame,shape,fingerX,fingerY);
        return candidates.size()==1?candidates.get(0):null;
    }
    /** A deliberate sub-cell pointer move distinguishes continuous held motion
     * from stationary or whole-cell-snapped previews. Ambiguity never releases. */
    public static Origin locateMoving(Game.Image im,Game.Image before,Game.Image previous,Game.Frame frame,Game.Shape shape,
            float fingerX,float fingerY,float dx,float dy) {
        if(im.width!=previous.width||im.height!=previous.height)return null;
        List<Origin> now=stagedCandidates(im,before,frame,shape,fingerX,fingerY);
        List<Origin> old=stagedCandidates(previous,before,frame,shape,fingerX-dx,fingerY-dy);
        // Drag rendering can be delayed by a frame and can quantize to grid
        // pixels. Compare actual displacement with the commanded probe rather
        // than requiring nearly exact sub-pixel movement.
        Origin selected=null;
        float bestError=Float.MAX_VALUE;
        float secondError=Float.MAX_VALUE;
        for(Origin n:now) for(Origin o:old) {
            float movedX=n.x-o.x,movedY=n.y-o.y;
            // Actual Android Classic drag renders in board-cell increments:
            // the captured incident moved ~78 px on a commanded ~52 px probe
            // (cell ~80 px). Quantization and a biased render-frame sample may
            // exceed the old .34-cell tolerance even though the *five* newly
            // rendered cells move together. Allow up to .58 cell error, but
            // still require strong same-direction motion of the whole shape.
            float tolerance=frame.cell*.58f;
            if(Math.abs(movedX-dx)>tolerance||Math.abs(movedY-dy)>tolerance)continue;
            float command=(float)Math.hypot(dx,dy);
            float moved=(float)Math.hypot(movedX,movedY);
            if(command<frame.cell*.2f||moved<frame.cell*.35f ||
               moved>command+frame.cell*.58f)continue;
            if(dx*movedX+dy*movedY < (dx*dx+dy*dy)*.55f)continue;
            float error=(movedX-dx)*(movedX-dx)+(movedY-dy)*(movedY-dy);
            if(error<bestError) {
                secondError=bestError;
                bestError=error;
                selected=n;
            } else if(error<secondError)secondError=error;
        }
        // Multiple equally plausible matches are unsafe: do not release.
        if(secondError-bestError < frame.cell*frame.cell*.025f)return null;
        return selected;
    }
    private static List<Origin> stagedCandidates(Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,float fingerX,float fingerY) {
        List<Origin> result=new ArrayList<>();
        if(im.width!=before.width||im.height!=before.height)return result;
        float cell=frame.cell,step=Math.max(2,cell*.08f);
        float minX=Math.max(0,fingerX-(shape.piece.w+1)*cell),maxX=Math.min(im.width-shape.piece.w*cell,fingerX+cell);
        float minY=0,maxY=Math.min(im.height-shape.piece.h*cell,fingerY+cell*.5f);
        List<float[]> seeds=new ArrayList<>();
        for(float y=minY;y<=maxY;y+=step)for(float x=minX;x<=maxX;x+=step) {
            float value=score(im,before,shape.piece,shape.color,x,y,cell,.3f,true);
            // Candidate needs all occupied CELL CENTERS to be newly painted,
            // plus >=80% color coverage and mostly clean outer edges. This
            // tolerates a narrow HUD occlusion but not a stationary board tile.
            if(value<.86f)continue;
            float[] seed=null;
            for(float[] candidate:seeds)if(Math.abs(x-candidate[0])<cell*.55f&&Math.abs(y-candidate[1])<cell*.55f){seed=candidate;break;}
            if(seed==null) {
                if(seeds.size()>=12)return result; // too many plausible objects
                seeds.add(new float[]{x,y,x,y,value});
            } else if(value>seed[4]){seed[2]=x;seed[3]=y;seed[4]=value;}
        }
        for(float[] seed:seeds) {
            Origin origin=refine(im,before,frame,shape,minX,maxX,minY,maxY,.3f,true,seed[2],seed[3],seed[4]);
            boolean duplicate=false;
            for(Origin old:result)if(Math.abs(origin.x-old.x)<cell*.25f&&Math.abs(origin.y-old.y)<cell*.25f)duplicate=true;
            if(!duplicate)result.add(origin);
        }
        return result;
    }
    private static Origin search(Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,float minX,float maxX,float minY,float maxY,float minNovel,boolean changedCenters) {
        if(im.width!=before.width||im.height!=before.height)return null;
        float cell=frame.cell,step=Math.max(2,cell*.08f);
        float best=.89f,bx=0,by=0;
        for(float y=minY;y<=maxY;y+=step)for(float x=minX;x<=maxX;x+=step) {
            float s=score(im,before,shape.piece,shape.color,x,y,cell,minNovel,changedCenters);
            if(s>best){best=s;bx=x;by=y;}
        }
        if(best<.9f)return null;
        return refine(im,before,frame,shape,minX,maxX,minY,maxY,minNovel,changedCenters,bx,by,best);
    }
    private static Origin refine(Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,float minX,float maxX,float minY,float maxY,
            float minNovel,boolean changedCenters,float bx,float by,float best) {
        float cell=frame.cell,step=Math.max(2,cell*.08f);
        // Average the whole local plateau, not just one step around its first
        // sampled edge. Thick frames/downsampling can make that edge asymmetric.
        float radius=cell*.45f,sx=0,sy=0;int count=0;
        for(float y=by-radius;y<=by+radius;y+=Math.max(1,step/4))for(float x=bx-radius;x<=bx+radius;x+=Math.max(1,step/4))
            if(x>=minX&&y>=minY&&x<=maxX&&y<=maxY&&score(im,before,shape.piece,shape.color,x,y,cell,minNovel,changedCenters)>=best-.001f){sx+=x;sy+=y;count++;}
        return new Origin(count==0?bx:sx/count,count==0?by:sy/count,best);
    }
}
