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
    // Candidate colored material; grid/tray geometry and local background contrast
    // decide occupancy. Dark saturated blocks must not require a bright face.
    public static boolean isTile(int c){return hi(c)>75 && hi(c)-Math.min(r(c),Math.min(g(c),b(c)))>40;}
    private static float hue(int c) {
        int red=r(c),green=g(c),blue=b(c),max=hi(c),min=Math.min(red,Math.min(green,blue));
        if(max==min)return 0;
        float hue=max==red?(green-blue)/(float)(max-min):max==green?2+(blue-red)/(float)(max-min):4+(red-green)/(float)(max-min);
        return hue<0?hue+6:hue;
    }
    private static float hueDistance(int a,int b) {
        float delta=Math.abs(hue(a)-hue(b));return Math.min(delta,6-delta);
    }
    private static boolean paleTile(int c,int bg) {
        return r(c)>185 && g(c)>175 && b(c)>155 && r(c)-b(c)>13 &&
            light(c)>light(bg)+42 && distance(c,bg)>130;
    }
    private static boolean faceCandidate(int c,int bg) {return isTile(c)||paleTile(c,bg);}
    private static boolean sameMaterial(int c,int material) {
        return (hueDistance(c,material)<.3f || (r(material)>185 && g(material)>175 && b(material)>155 && r(material)-b(material)>13 && distance(c,material)<75))&&distance(c,material)<125&&hi(c)>hi(material)*.65f;
    }
    private static float line(Game.Image im,float x,float y,boolean vertical) {
        float v=255;
        for(int d=-2;d<=2;d++)v=Math.min(v,light(im.at(x+(vertical?d:0),y+(vertical?0:d))));
        return v;
    }
    private static float gridScore(Game.Image im,float l,float t,float p) {
        if(t+8*p>im.height*.82f || p<im.width*.07f) return -1;
        float sum=0,gridLight=0;int agrees=0;
        for(int i=1;i<8;i++)for(int j=0;j<8;j++) {
            float x=l+i*p,y=t+(j+.5f)*p;
            gridLight+=line(im,x,y,true);
            float contrast=(light(im.at(x-p*.22f,y))+light(im.at(x+p*.22f,y)))*.5f-line(im,x,y,true);
            sum+=Math.min(35,Math.max(-20,contrast));if(contrast>3)agrees++;
            x=l+(j+.5f)*p;y=t+i*p;
            gridLight+=line(im,x,y,false);
            contrast=(light(im.at(x,y-p*.22f))+light(im.at(x,y+p*.22f)))*.5f-line(im,x,y,false);
            sum+=Math.min(35,Math.max(-20,contrast));if(contrast>3)agrees++;
        }
        float border=0;int topGood=0,bottomGood=0;
        for(int j=0;j<8;j++) {
            float x=l+(j+.5f)*p,y=t+8*p;
            float bottom=light(im.at(x,y-p*.2f))-line(im,x,y,false);
            float top=light(im.at(x,t+p*.2f))-line(im,x,t,false);
            if(bottom>3&&line(im,x,y,false)<gridLight/112+25)bottomGood++;
            if(top>3&&line(im,x,t,false)<gridLight/112+25)topGood++;
            border+=Math.min(35,bottom)+Math.min(35,top);
        }
        return agrees<85||topGood<6||bottomGood<6?-1:sum/112f+border/64f;
    }
    public enum Issue { NONE, FORMAT, BLACK_FRAME, GRID, BOARD_REFERENCE, BOARD_CELL, BOARD_ANIMATION, TRAY }
    public static final class Detection {
        public final Game.Frame frame;
        public final Issue issue;
        public final String reason;
        public final int slot,row,column;
        private Detection(Game.Frame frame,Issue issue,String reason,int slot,int row,int column) {
            this.frame=frame;this.issue=issue;this.reason=reason;this.slot=slot;this.row=row;this.column=column;
        }
        public String code(){return issue+":"+reason+":"+slot+":"+row+":"+column;}
    }
    private static Detection failure(Issue issue,String reason,int slot,int row,int column) {
        return new Detection(null,issue,reason,slot,row,column);
    }
    /** Compatibility entry point: only fully read frames may reach planning. */
    public static Game.Frame read(Game.Image im) { return analyze(im).frame; }
    public static Detection analyze(Game.Image im) {
        if(im.width<300 || im.height<im.width*1.15f) return failure(Issue.FORMAT,"PORTRAIT_SIZE",-1,-1,-1);
        boolean black=true;
        for(int y=0;y<8;y++)for(int x=0;x<8;x++)if(hi(im.at((x+.5f)*im.width/8,(y+.5f)*im.height/8))>8)black=false;
        if(black)return failure(Issue.BLACK_FRAME,"NO_VISIBLE_CONTENT",-1,-1,-1);
        int step=Math.max(2,im.width/350);
        float best=5,l=0,t=0,p=0;
        for(int y=(int)(im.height*.13f);y<im.height*.56f;y+=step) {
            for(int threshold:new int[]{30,45,65,85,105,125,145,165,185,210}) {
                int start=-1;
                for(int x=0;x<im.width;x++) {
                    if(light(im.at(x,y))<threshold) {if(start<0)start=x;}
                    else if(start>=0) {
                        int size=x-start;
                        if(start>im.width*.015 && start<im.width*.115 && size>im.width*.80 && size<im.width*.97) {
                            float pitch=size/8f,score=gridScore(im,start,y,pitch);
                            if(score>best){best=score;l=start;t=y;p=pitch;}
                        }
                        start=-1;
                    }
                }
            }
        }
        if(p==0)return failure(Issue.GRID,"NO_8X8_GRID",-1,-1,-1);
        long board=0;
        // Empty cells have the same interior material at center and all four
        // inset corners. An occupied dark center is NOT a background reference.
        // Two different flat dark materials are ambiguous: reject, never invert.
        // Identify the empty board color by consensus among ALL 64 cell
        // interiors. The previous first-flat-cell reference failed on themed
        // beveled blocks (e.g., the blue screenshot, row 1 column 7).
        int[] centers=new int[64];
        for(int y=0;y<8;y++)for(int x=0;x<8;x++)
            centers[y*8+x]=im.at(l+(x+.5f)*p,t+(y+.5f)*p);
        int baseIndex=-1,dominant=0;
        for(int i=0;i<64;i++){
            int matches=0;
            for(int j=0;j<64;j++)if(distance(centers[i],centers[j])<=24)matches++;
            if(matches>dominant){dominant=matches;baseIndex=i;}
        }
        // An unreliable reference is never enough to recommend a move.
        if(dominant<6)return failure(Issue.BOARD_REFERENCE,"NO_DOMINANT_EMPTY_REFERENCE",-1,-1,-1);
        int red=0,green=0,blue=0,n=0;
        for(int c:centers)if(distance(c,centers[baseIndex])<=24){
            red+=r(c);green+=g(c);blue+=b(c);n++;
        }
        int baseline=(red/n<<16)|(green/n<<8)|(blue/n);
        for(int y=0;y<8;y++)for(int x=0;x<8;x++){
            int center=centers[y*8+x],empty=0,filled=0,other=0;
            for(float dy:new float[]{-.17f,0f,.17f})for(float dx:new float[]{-.17f,0f,.17f}){
                int c=im.at(l+(x+.5f+dx)*p,t+(y+.5f+dy)*p);
                int delta=distance(c,baseline);
                if(delta<=34)empty++;
                else if(delta>48 && faceCandidate(c,baseline))filled++;
                else other++;
            }
            if(empty>=8 && distance(center,baseline)<=34)continue;
            // Dark/palette-similar surfaces are deliberately held for manual
            // review instead of incorrectly marking them empty or occupied.
            if(filled>=7 && distance(center,baseline)>48 && hi(center)>112 &&
                (light(center)>light(baseline)+13 || hueDistance(center,baseline)>.34f)){
                board|=1L<<(y*8+x);
            } else return failure(Issue.BOARD_CELL,"UNRELIABLE_CELL_EVIDENCE",-1,y,x);
        }
        List<Game.Shape> shapes=new ArrayList<>();
        // A completed line is an animation, not a stable actionable game board.
        for(long row:Rules.ROWS)if((board&row)==row)return failure(Issue.BOARD_ANIMATION,"CLEARING_ROW",-1,-1,-1);
        for(long col:Rules.COLS)if((board&col)==col)return failure(Issue.BOARD_ANIMATION,"CLEARING_COLUMN",-1,-1,-1);
        for(int slot=0;slot<3;slot++) {
            Tray result=tray(im,l,t,p,slot);
            if(result.state==SlotState.UNCERTAIN)return failure(Issue.TRAY,result.reason,slot,-1,-1);
            if(result.shape!=null)shapes.add(result.shape);
        }
        return new Detection(new Game.Frame(board,shapes,l,t,p,im.width,im.height,Math.min(1,best/16)),Issue.NONE,"OK",-1,-1,-1);
    }
    private enum SlotState { EMPTY, RECOGNIZED, UNCERTAIN }
    private static final class Tray {
        final Game.Shape shape; final SlotState state; final String reason;
        Tray(Game.Shape shape,SlotState state,String reason){this.shape=shape;this.state=state;this.reason=reason;}
    }
    private static final Tray EMPTY=new Tray(null,SlotState.EMPTY,"EMPTY");
    private static Tray uncertain(String reason){return new Tray(null,SlotState.UNCERTAIN,reason);}
    /** Excluded material may be a narrow bevel/cast shadow, but not a broad
     * unexplained face. Summed areas keep this check linear in pocket pixels. */
    private static boolean hasSolidPatch(boolean[] mask,int w,int h,int edge) {
        if(edge>w||edge>h)return false;
        int stride=w+1;int[] sums=new int[(w+1)*(h+1)];
        for(int y=1;y<=h;y++) {
            int row=0;
            for(int x=1;x<=w;x++) {if(mask[(y-1)*w+x-1])row++;sums[y*stride+x]=sums[(y-1)*stride+x]+row;}
        }
        for(int y=edge;y<=h;y++)for(int x=edge;x<=w;x++) {
            int count=sums[y*stride+x]-sums[(y-edge)*stride+x]-sums[y*stride+x-edge]+sums[(y-edge)*stride+x-edge];
            if(count>=edge*edge*.9f)return true;
        }
        return false;
    }
    private static Tray tray(Game.Image im,float l,float t,float p,int slot) {
        int x1=(int)(l+slot*8*p/3),x2=(int)(l+(slot+1)*8*p/3);
        int y1=(int)(t+8.7f*p),y2=Math.min(im.height-1,(int)(t+11.9f*p));
        if(y2<=y1)return uncertain("OUTSIDE_CAPTURE");
        Map<Integer,Integer> colors=new HashMap<>();
        for(int y=y1;y<y2;y+=4)for(int x=x1;x<x2;x+=4) {
            int c=im.at(x,y),key=((r(c)/16)<<8)|((g(c)/16)<<4)|(b(c)/16);
            colors.put(key,colors.getOrDefault(key,0)+1);
        }
        int bg=colors.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        bg=((bg>>8&15)*16+8)<<16 | ((bg>>4&15)*16+8)<<8 | (bg&15)*16+8;
        // A bright-only mask loses dark material; a broad color mask joins the
        // bevel shadows of adjacent cells. Estimate this pocket's face material
        // first, then keep pixels near that color (rather than its cast shadow).
        Map<Integer,Integer> materials=new HashMap<>();
        for(int y=y1;y<y2;y+=2)for(int x=x1;x<x2;x+=2) {
            int c=im.at(x,y);
            if(faceCandidate(c,bg)&&distance(c,bg)>72 && (hueDistance(c,bg)>.36f || hi(c)>hi(bg)+45 || (b(bg)>65 && light(c)>light(bg)+30))) {
                int key=((r(c)/16)<<8)|((g(c)/16)<<4)|(b(c)/16);
                materials.put(key,materials.getOrDefault(key,0)+1);
            }
        }
        if(materials.isEmpty()) {
            for(int y=y1;y<y2;y+=2)for(int x=x1;x<x2;x+=2) {
                int c=im.at(x,y);
                if(faceCandidate(c,bg)&&distance(c,bg)>72) {
                    int key=((r(c)/16)<<8)|((g(c)/16)<<4)|(b(c)/16);
                    materials.put(key,materials.getOrDefault(key,0)+1);
                }
            }
        }
        int material=0;
        if(!materials.isEmpty()) {
            int key=materials.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            material=((key>>8&15)*16+8)<<16|((key>>4&15)*16+8)<<8|(key&15)*16+8;
        }
        int rw=x2-x1,rh=y2-y1;
        boolean[] mask=new boolean[rw*rh],unexplained=new boolean[rw*rh];
        int minX=rw,minY=rh,maxX=-1,maxY=-1,foreground=0,unmodeledBright=0,modeled=0;
        for(int y=0;y<rh;y++)for(int x=0;x<rw;x++) {
            int c=im.at(x1+x,y1+y);
            int difference=distance(c,bg);
            if(difference>65)foreground++;
            boolean face=material!=0&&faceCandidate(c,bg)&&difference>72&&sameMaterial(c,material);
            boolean unknownBright=!faceCandidate(c,bg)&&difference>80&&(hi(c)>hi(bg)+20||hi(c)-Math.min(r(c),Math.min(g(c),b(c)))<25);
            if(unknownBright)unmodeledBright++;
            // Colored gold plates around real-game tiles are narrow decoration;
            // a broad excluded face is evidence of an unreadable/covered block.
            // Compare against the locally sampled background rather than the
            // quantized pocket-wide mode: purple gradients must not look like
            // a solid occluding rectangle. Preserve strict rejection of
            // genuine foreign-color surfaces.
            int localBackground=im.at(x1+x,Math.max(0,y1-12));
            boolean foreign=material!=0 && isTile(c)
                && hueDistance(c,material)>.33f
                && distance(c,localBackground)>68
                && (hi(c)>hi(localBackground)+25 || distance(c,material)>115);
            boolean lightUnknown=unknownBright && distance(c,localBackground)>75;
            boolean lowContrastForeign=!face && isTile(c) && distance(c,bg)>28 && (hi(c)<hi(bg)+15 || (distance(c,material)>85 && distance(c,localBackground)>70));
            unexplained[y*rw+x]=!face&&(foreign||lightUnknown||lowContrastForeign);
            if(face) {
                modeled++;
                mask[y*rw+x]=true;minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
            }
        }
        boolean broadUnknown=hasSolidPatch(unexplained,rw,rh,Math.max(4,(int)(p*.26f)));
        if(maxX<minX)return !broadUnknown&&foreground<p*p*.04?EMPTY:uncertain("UNSUPPORTED_COLOR");
        if(unmodeledBright>Math.max(p*p*.04,modeled*.15)||broadUnknown)return uncertain("OCCLUDED_OR_UNKNOWN_COLOR");
        // Separate beveled square faces. Their measured sizes set the lattice pitch;
        // a tiny gap does not add a phantom row or column.
        int centeredArea=0;List<Integer> sizes=new ArrayList<>();List<Float> centersX=new ArrayList<>(),centersY=new ArrayList<>();
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
            if(tail>p*p*.025 && bx-ax<p*.68 && by-ay<p*.68) {
                centeredArea+=tail;sizes.add(bx-ax+1);sizes.add(by-ay+1);centersX.add((ax+bx)*.5f);centersY.add((ay+by)*.5f);
            }
        }
        if(sizes.isEmpty())return uncertain("NO_SEPARATE_FACES");
        Collections.sort(sizes);float unit=sizes.get(sizes.size()/2)+1;
        // Center-to-center pitch includes the gold frame and gaps. Face width
        // alone underestimates the pitch in the real purple event theme.
        List<Float> intervals=new ArrayList<>();
        for(List<Float> axis:Arrays.asList(centersX,centersY)) {
            List<Float> sorted=new ArrayList<>(axis);Collections.sort(sorted);
            float previous=sorted.get(0);
            for(int i=1;i<sorted.size();i++)if(sorted.get(i)-previous>p*.2f) {
                float interval=sorted.get(i)-previous;
                if(interval>=p*.26f&&interval<=p*.68f)intervals.add(interval);
                previous=sorted.get(i);
            }
        }
        boolean centersReliable=!intervals.isEmpty()&&centeredArea>=modeled*.7f;
        if(centersReliable) {
            Collections.sort(intervals);float measured=intervals.get(intervals.size()/2);
            // A merged outer column/row can be less than30% of the material.
            // Every modeled pixel must fit, regardless of the area ratio.
            centersReliable=minX>=Collections.min(centersX)-measured*.5f-2&&maxX<=Collections.max(centersX)+measured*.5f+2&&
                minY>=Collections.min(centersY)-measured*.5f-2&&maxY<=Collections.max(centersY)+measured*.5f+2;
            // Even when centers do not span the complete piece, their spacing
            // remains useful for reconstructing its complete mask bounds.
            unit=measured;
        }
        float firstX,firstY,stepX,stepY;int cols,rows;
        if(centersReliable) {
            Collections.sort(intervals);unit=intervals.get(intervals.size()/2);
            firstX=Collections.min(centersX);firstY=Collections.min(centersY);
            cols=Math.round((Collections.max(centersX)-firstX)/unit)+1;rows=Math.round((Collections.max(centersY)-firstY)/unit)+1;
            for(int i=0;i<centersX.size();i++) {
                float gx=(centersX.get(i)-firstX)/unit,gy=(centersY.get(i)-firstY)/unit;
                if(Math.abs(gx-Math.round(gx))>.18f||Math.abs(gy-Math.round(gy))>.18f)return uncertain("IRREGULAR_LATTICE");
            }
            stepX=unit;stepY=unit;
        } else {
            // Downsampling can merge adjacent faces. Do not silently drop the
            // merged component: retain the complete mask bounds in this case.
            cols=Math.round((maxX-minX+1)/unit);rows=Math.round((maxY-minY+1)/unit);
            if(cols<1||rows<1)return uncertain("SHAPE_BOUNDS");
            stepX=(maxX-minX+1)/(float)cols;stepY=(maxY-minY+1)/(float)rows;
            if(Math.abs(stepX-stepY)>unit*.2f)return uncertain("IRREGULAR_LATTICE");
            firstX=minX+stepX*.5f;firstY=minY+stepY*.5f;
        }
        if(unit<p*.26 || unit>p*.68)return uncertain("FACE_SIZE");
        if(cols<1||cols>5||rows<1||rows>5)return uncertain("SHAPE_BOUNDS");
        List<Integer> coords=new ArrayList<>();float pickX=0,pickY=0,near=Float.MAX_VALUE;int color=0;
        for(int y=0;y<rows;y++)for(int x=0;x<cols;x++) {
            int hits=0;
            for(float dy:new float[]{-.2f,0,.2f})for(float dx:new float[]{-.2f,0,.2f}) {
                int sx=Math.round(firstX+(x+dx)*stepX),sy=Math.round(firstY+(y+dy)*stepY);
                if(sx<0||sx>=rw||sy<0||sy>=rh)return uncertain("OUTSIDE_CAPTURE");
                if(mask[sy*rw+sx])hits++;
            }
            if(hits>=7) {
                coords.add(x);coords.add(y);
                float d=(x-(cols-1)*.5f)*(x-(cols-1)*.5f)+(y-(rows-1)*.5f)*(y-(rows-1)*.5f);
                if(d<near){near=d;pickX=x1+firstX+x*stepX;pickY=y1+firstY+y*stepY;color=im.at(pickX,pickY);}
            } else if(hits>1)return uncertain("PARTIAL_FACE");
        }
        if(coords.isEmpty())return uncertain("NO_FILLED_CELL");
        int[] cells=coords.stream().mapToInt(i->i).toArray();
        Game.Piece piece=new Game.Piece(cells);
        // Reject disconnected decoration, but accept diagonal-connected game pieces.
        boolean[] reach=new boolean[piece.xs.length];reach[0]=true;int count=1;
        for(int k=0;k<piece.xs.length;k++)for(int a=0;a<reach.length;a++)if(reach[a])for(int b=0;b<reach.length;b++)
            if(!reach[b]&&Math.abs(piece.xs[a]-piece.xs[b])<=1&&Math.abs(piece.ys[a]-piece.ys[b])<=1){reach[b]=true;count++;}
        if(count!=reach.length)return uncertain("DISCONNECTED_PIXELS");
        return new Tray(new Game.Shape(slot,piece,pickX,pickY,color),SlotState.RECOGNIZED,"OK");
    }
}
