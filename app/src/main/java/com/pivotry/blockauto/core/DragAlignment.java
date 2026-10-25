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
            int matched=0,bright=0;
            for(float oy:new float[]{.39f,.61f})for(float ox:new float[]{.39f,.61f}) {
                int px= Math.round(left+ox*cell), py=Math.round(top+oy*cell);
                if(px<0||py<0||px>=current.width||py>=current.height)continue;
                int now=current.pixels[py*current.width+px];
                int was=before.pixels[py*before.width+px];
                if(previewMaterial(now,held.color) && Recognizer.distance(now,was)>60) {
                    matched++;
                    if(same(now,held.color))bright++;
                }
            }
            // Each square needs complete, newly changed material AND
            // at least two strict bright-face pixels. Dark beveled halves
            // alone cannot turn an unrelated background into a preview.
            if(matched<4 || bright<2)return false;
        }
        return true;
    }

    private static boolean same(int c,int target) {
        return Recognizer.isTile(c)&&Recognizer.hi(c)>Recognizer.hi(target)*.78f&&Recognizer.distance(c,target)<125;
    }
    /** The face may have a dark beveled lower half. This is ONLY used as
     * supplementary evidence alongside strict bright-face probes. */
    private static boolean previewMaterial(int c,int target) {
        if(same(c,target))return true;
        if(!Recognizer.isTile(c)||Recognizer.hi(c)<Recognizer.hi(target)*.48f)return false;
        return hueDistance(c,target)<.21f;
    }
    private static float hue(int c) {
        int r=Recognizer.r(c),g=Recognizer.g(c),b=Recognizer.b(c);
        int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
        if(max==min)return -1f;
        float h=max==r?(g-b)/(float)(max-min):
            max==g?2f+(b-r)/(float)(max-min):4f+(r-g)/(float)(max-min);
        return h<0?h+6f:h;
    }
    private static float hueDistance(int a,int b) {
        float x=hue(a),y=hue(b);
        if(x<0||y<0)return Float.MAX_VALUE;
        float d=Math.abs(x-y);
        return Math.min(d,6f-d);
    }

    /**
     * v1.4.5 diagnostic (11): cyan 1x4 is projected in its top four target
     * cells while the genuinely held 1x4 is rendered directly BELOW it.
     * Their identical columns form eight apparent tiles, defeating the
     * standalone full-shape tracker. This narrowly scoped fallback requires
     * BOTH an entire legal, newly colored target preview and independent
     * fresh cyan face evidence at the actual held finger. It is a grid
     * preview proxy, never a measured moving-sprite origin: callers MUST
     * NOT train gesture calibration from it. Must remain stable across frames
     * and pass the independent post-release board+tray verifier.
     */
    public static Origin locateHeldBelowSnappedPreview(Game.Image current,
            Game.Image before,Game.Frame frame,Game.Shape held,
            float targetX,float targetY,float fingerX,float fingerY) {
        if(current==null||before==null||frame==null||held==null ||
           current.width!=before.width||current.height!=before.height||
           held.piece.w!=1||held.piece.h<3||held.piece.h>5||
           !Float.isFinite(fingerX)||!Float.isFinite(fingerY))return null;
        float cell=frame.cell;
        if(!Float.isFinite(cell)||cell<=10f)return null;
        if(Math.abs(fingerX-(targetX+cell*.5f))>cell*.85f)return null;
        float end=targetY+held.piece.h*cell;
        // Guard against an old tray thumbnail or an unrelated blue tile.
        if(fingerY<end+cell*.15f || fingerY>end+cell*3.0f)return null;
        if(fingerX<cell*.15f||fingerX>=current.width-cell*.15f||
           fingerY<cell*.15f||fingerY>=current.height-cell*.15f)return null;
        int col=Math.round((targetX-frame.left)/cell);
        int row=Math.round((targetY-frame.top)/cell);
        if(Math.abs(frame.left+col*cell-targetX)>cell*.06f||
           Math.abs(frame.top+row*cell-targetY)>cell*.06f)return null;
        if(!targetCellPreview(current,before,frame,held,col,row))return null;
        int count=0,bright=0;
        for(float dy:new float[]{-.12f,.12f})for(float dx:new float[]{-.12f,.12f}) {
            float x=fingerX+dx*cell,y=fingerY+dy*cell;
            int c=current.at(x,y),was=before.at(x,y);
            if(previewMaterial(c,held.color)&&Recognizer.distance(c,was)>60) {
                count++;
                if(same(c,held.color))bright++;
            }
        }
        // A dark shade is valid only because the target cells separately
        // passed the full bright+dark test above. Require all finger pixels.
        if(count!=4)return null;
        return new Origin(targetX,targetY,.9f);
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

    /**
     * Diagnostic (13): an already occupied, same-colour BOARD cell can hide
     * one pixel-center of an actually held 2x3 green piece. This recovery
     * is restricted to the staged ALIGN phase by its caller; it NEVER
     * authorizes releasing a piece at the target on its own.
     *
     * Require FIVE fresh centers of SIX, one remaining old center in an
     * explicitly occupied board cell (not any tray or arbitrary screenshot
     * decoration), high new-pixel coverage, and a unique whole-piece match.
     */
    public static Origin locateOverOccupiedBoard(Game.Image im,Game.Image before,
            Game.Frame frame,Game.Shape shape,float fingerX,float fingerY){
        if(im==null||before==null||frame==null||shape==null||
           im.width!=before.width||im.height!=before.height||
           !Float.isFinite(fingerX)||!Float.isFinite(fingerY)||
           !Float.isFinite(frame.cell)||frame.cell<=10f||
           shape.piece.w!=2||shape.piece.h!=3||shape.piece.xs.length!=6)
            return null;
        float cell=frame.cell,step=Math.max(2f,cell*.10f);
        float minX=Math.max(0,fingerX-(shape.piece.w+2.5f)*cell);
        float maxX=Math.min(im.width-shape.piece.w*cell,fingerX+3f*cell);
        float minY=Math.max(0,fingerY-8.5f*cell);
        float maxY=Math.min(im.height-shape.piece.h*cell,fingerY-.8f*cell);
        if(minX>maxX||minY>maxY)return null;
        List<float[]> seeds=new ArrayList<>();
        for(float y=minY;y<=maxY;y+=step)for(float x=minX;x<=maxX;x+=step){
            // Score includes all six colored squares; shade samples must
            // have >=62% novelty. The single old center is checked below.
            float strength=score(im,before,shape.piece,shape.color,
                x,y,cell,.62f,false);
            if(strength<.89f||
               !oneOldBoardCenter(im,before,frame,shape,x,y))continue;
            float[] group=null;
            for(float[] c:seeds)if(Math.abs(c[0]-x)<cell*.55f &&
                               Math.abs(c[1]-y)<cell*.55f){group=c;break;}
            if(group==null){
                if(seeds.size()>=2)return null; // ambiguous whole shapes
                seeds.add(new float[]{x,y,x,y,strength});
            }else if(strength>group[4]){
                group[2]=x;group[3]=y;group[4]=strength;
            }
        }
        if(seeds.size()!=1)return null;
        float[] best=seeds.get(0);
        Origin found=refine(im,before,frame,shape,minX,maxX,minY,maxY,
            .62f,false,best[2],best[3],best[4]);
        return oneOldBoardCenter(im,before,frame,shape,found.x,found.y)
            ?found:null;
    }

    private static boolean oneOldBoardCenter(Game.Image im,Game.Image before,
            Game.Frame frame,Game.Shape shape,float x,float y){
        float cell=frame.cell;
        int fresh=0,oldBoard=0;
        for(int i=0;i<shape.piece.xs.length;i++){
            float cx=x+(shape.piece.xs[i]+.5f)*cell;
            float cy=y+(shape.piece.ys[i]+.5f)*cell;
            int now=im.at(cx,cy),was=before.at(cx,cy);
            if(!same(now,shape.color))return false;
            if(Recognizer.distance(now,was)>60){fresh++;continue;}
            if(!same(was,shape.color))return false;
            int col=(int)Math.floor((cx-frame.left)/cell);
            int row=(int)Math.floor((cy-frame.top)/cell);
            if(col<0||col>=8||row<0||row>=8||
               (frame.board&(1L<<(row*8+col)))==0L)return false;
            // The old unchanged centre MUST belong near the middle
            // of that occupied board cell, not an arbitrary color patch.
            float centerX=frame.left+(col+.5f)*cell;
            float centerY=frame.top+(row+.5f)*cell;
            if(Math.abs(cx-centerX)>cell*.35f||
               Math.abs(cy-centerY)>cell*.35f)return false;
            oldBoard++;
        }
        return fresh==5&&oldBoard==1;
    }

    /** A deliberate sub-cell pointer move distinguishes continuous held motion
     * from stationary or whole-cell-snapped previews. Ambiguity never releases. */
    public static Origin locateMoving(Game.Image im,Game.Image before,Game.Image previous,Game.Frame frame,Game.Shape shape,
            float fingerX,float fingerY,float dx,float dy) {
        Motion motion=locateMovingEvidence(im,before,previous,frame,shape,fingerX,fingerY,dx,dy);
        return motion==null?null:motion.latest;
    }
    /** Origin and measured displacement from the SAME verified before/after
     * sprite pair. This avoids inferring motor gain from finger offsets or
     * unverified color matches and reuses the existing strict uniqueness proof.
     */
    public static final class Motion {
        public final Origin latest;
        public final float movedX,movedY;
        Motion(Origin latest,float mx,float my){
            this.latest=latest;this.movedX=mx;this.movedY=my;
        }
    }
    public static Motion locateMovingEvidence(Game.Image im,Game.Image before,Game.Image previous,Game.Frame frame,Game.Shape shape,
            float fingerX,float fingerY,float dx,float dy) {
        if(im.width!=previous.width||im.height!=previous.height)return null;
        List<Origin> now=stagedCandidates(im,before,frame,shape,fingerX,fingerY);
        List<Origin> old=stagedCandidates(previous,before,frame,shape,fingerX-dx,fingerY-dy);
        // Drag rendering can be delayed by a frame and can quantize to grid
        // pixels. Compare actual displacement with the commanded probe rather
        // than requiring nearly exact sub-pixel movement.
        Motion selected=null;
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
                selected=new Motion(n,movedX,movedY);
            } else if(error<secondError)secondError=error;
        }
        // Multiple equally plausible matches are unsafe: do not release.
        if(secondError-bestError < frame.cell*frame.cell*.025f)return null;
        return selected;
    }
    /**
     * Retry-only recovery when the PICKED sprite overlaps its own small
     * tray thumbnail. In user v1.3.6 the before.png thumbnail at x~361
     * overlaps TWO of four final L-piece cell centers at x~382. The normal
     * matcher correctly requires every center to be novel, and thus misses
     * the genuine -78px movement ordered by a -52px probe.
     *
     * Keep STRICT move direction verification. Loosen ONLY novelty versus
     * the pre-pick frame, and ONLY with the same complete shape, >=half newly
     * painted centers, and a unique previous/current moving pair. Never use
     * this result alone to release the piece at a target.
     */
    public static Origin locatePickedAfterRetry(Game.Image im,Game.Image before,
            Game.Image previous,Game.Frame frame,Game.Shape shape,
            float fingerX,float fingerY,float retryDX,float retryDY) {
        Motion motion=locatePickedAfterRetryEvidence(im,before,previous,frame,shape,
            fingerX,fingerY,retryDX,retryDY);
        return motion==null?null:motion.latest;
    }
    public static Motion locatePickedAfterRetryEvidence(Game.Image im,Game.Image before,
            Game.Image previous,Game.Frame frame,Game.Shape shape,
            float fingerX,float fingerY,float retryDX,float retryDY) {
        if(im==null||previous==null||before==null||frame==null||shape==null ||
           im.width!=previous.width||im.height!=previous.height ||
           im.width!=before.width||im.height!=before.height ||
           frame.cell<=0)return null;
        float cell=frame.cell;
        float command=(float)Math.hypot(retryDX,retryDY);
        if(command<cell*.30f || command>cell*1.5f)return null;
        // The first frame may overlap the thumbnail too; allow the same
        // limited exemption while requiring both frames to show an entire
        // well-shaped, mostly new sprite.
        List<Origin> now=stagedCandidates(im,before,frame,shape,fingerX,fingerY,true);
        List<Origin> old=stagedCandidates(previous,before,frame,shape,
                fingerX-retryDX,fingerY-retryDY,true);
        if(now.size()!=1 || old.size()!=1)return null;
        Origin n=now.get(0),o=old.get(0);
        if(n.confidence<.88f||o.confidence<.88f)return null;
        float mx=n.x-o.x,my=n.y-o.y;
        float moved=(float)Math.hypot(mx,my);
        if(moved<cell*.35f || moved>command+cell*.58f)return null;
        if(Math.abs(mx-retryDX)>cell*.58f ||
           Math.abs(my-retryDY)>cell*.58f)return null;
        if(retryDX*mx+retryDY*my < command*command*.55f)return null;
        return new Motion(n,mx,my);
    }

    private static List<Origin> stagedCandidates(Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,float fingerX,float fingerY) {
        return stagedCandidates(im,before,frame,shape,fingerX,fingerY,false);
    }
    private static List<Origin> stagedCandidates(Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,float fingerX,float fingerY,boolean allowThumbnailOverlap) {
        // Normally the sprite is above/left of the held finger. Some Android
        // Classic drag skins also render it 1.2-2.2 board cells to the RIGHT
        // of the finger. In the uploaded v1.3.4 diagnostic the previous
        // purple 1x2 origin was x=511 while previous fingerX=416. The
        // historical maxX=fingerX+cell=496 clipped away a valid moving
        // shape. First scan the fast, historical window, then only widen if
        // it was empty. Shape uniqueness and motion proof are still required.
        // The strict normal path first checks the fast nearby window.
        // Retry-with-overlap MUST inspect the entire allowed region:
        // accepting a single nearby shape while an indistinguishable second
        // copy is farther right would break the required uniqueness proof.
        if(allowThumbnailOverlap)
            return stagedCandidates(im,before,frame,shape,fingerX,fingerY,true,true);
        List<Origin> near=stagedCandidates(im,before,frame,shape,fingerX,fingerY,false,false);
        return near.isEmpty()
            ? stagedCandidates(im,before,frame,shape,fingerX,fingerY,true,false)
            : near;
    }
    private static List<Origin> stagedCandidates(Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,float fingerX,float fingerY,boolean expanded,boolean allowThumbnailOverlap) {
        List<Origin> result=new ArrayList<>();
        if(im.width!=before.width||im.height!=before.height)return result;
        float cell=frame.cell,step=Math.max(2,cell*.08f);
        float leftMargin=shape.piece.w+(expanded?2.5f:1f);
        float rightMargin=expanded?3f:1f;
        float minX=Math.max(0,fingerX-leftMargin*cell);
        float maxX=Math.min(im.width-shape.piece.w*cell,fingerX+rightMargin*cell);
        float minY=0,maxY=Math.min(im.height-shape.piece.h*cell,fingerY+cell*.5f);
        List<float[]> seeds=new ArrayList<>();
        for(float y=minY;y<=maxY;y+=step)for(float x=minX;x<=maxX;x+=step) {
            float minNovel=allowThumbnailOverlap?.55f:.3f;
            boolean allCentersChanged=!allowThumbnailOverlap;
            float value=score(im,before,shape.piece,shape.color,x,y,cell,
                minNovel,allCentersChanged);
            // Normal path keeps every center newly changed. Retry overlap
            // path still demands >= half newly painted centers and >55%
            // entirely new color samples, plus strong full-shape color/edges.
            if(value<.86f)continue;
            if(allowThumbnailOverlap &&
                !retryCentersAreNewOrOwnThumbnail(im,before,frame,shape,x,y))continue;
            float[] seed=null;
            for(float[] candidate:seeds)if(Math.abs(x-candidate[0])<cell*.55f&&Math.abs(y-candidate[1])<cell*.55f){seed=candidate;break;}
            if(seed==null) {
                if(seeds.size()>=12)return result; // too many plausible objects
                seeds.add(new float[]{x,y,x,y,value});
            } else if(value>seed[4]){seed[2]=x;seed[3]=y;seed[4]=value;}
        }
        for(float[] seed:seeds) {
            Origin origin=refine(im,before,frame,shape,minX,maxX,minY,maxY,
                allowThumbnailOverlap?.55f:.3f,!allowThumbnailOverlap,
                seed[2],seed[3],seed[4]);
            boolean duplicate=false;
            for(Origin old:result)if(Math.abs(origin.x-old.x)<cell*.25f&&Math.abs(origin.y-old.y)<cell*.25f)duplicate=true;
            if(!duplicate)result.add(origin);
        }
        return result;
    }
    /**
     * All occupied centers must either be newly painted OR lie inside this
     * exact tray slot's miniature source sprite. Previous generic 50% novelty
     * failed on the uploaded v1.4.0 green 1x5: its large lifted shape
     * overlapped THREE mini-tray cell centers (y=1114/1194/1274), leaving
     * only two changed centers even though the whole piece moved -78px.
     *
     * A same-colored STATIC board cell OUTSIDE this source slot's small
     * thumbnail is never allowed to complete a picked piece. Require >=2
     * independently new centers for long pieces, and the existing >55%
     * newly colored sample ratio, unique candidates and directional proof.
     */
    private static boolean retryCentersAreNewOrOwnThumbnail(
            Game.Image im,Game.Image before,Game.Frame frame,Game.Shape shape,
            float x,float y) {
        final Game.Piece p=shape.piece;
        final float cell=frame.cell;
        final float mini=cell*.46f;
        final float thumbnailHalfX=mini*(p.w+.15f);
        final float thumbnailHalfY=mini*(p.h*.60f+.25f);
        final float trayTop=frame.top+8f*cell+cell*.12f;
        int novel=0;
        for(int i=0;i<p.xs.length;i++) {
            final float cx=x+(p.xs[i]+.5f)*cell;
            final float cy=y+(p.ys[i]+.5f)*cell;
            final int current=im.at(cx,cy), original=before.at(cx,cy);
            if(!same(current,shape.color))return false;
            if(Recognizer.distance(current,original)>60) {
                novel++;
                continue;
            }
            // Source thumbnail may be a little off-center from the input
            // point (notably on L pieces where pickX is a noncentral cell).
            // Only the lower TRAY region and the selected piece's own source
            // footprint receive this exemption.
            boolean ownThumbnail=cy>=trayTop &&
                Math.abs(cx-shape.pickX)<=thumbnailHalfX &&
                Math.abs(cy-shape.pickY)<=thumbnailHalfY &&
                same(original,shape.color);
            if(!ownThumbnail)return false;
        }
        return novel>=Math.min(2,p.xs.length);
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
