package com.pivotry.blockauto.core;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** Also runnable with plain javac/java: no Android or test framework required. */
public final class CoreChecks {
    static int checks;
    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
    static Game.Image load(String name) throws Exception {
        BufferedImage b = ImageIO.read(new File("tests/fixtures/"+name));
        return new Game.Image(b.getWidth(), b.getHeight(),
            b.getRGB(0,0,b.getWidth(),b.getHeight(),null,0,b.getWidth()));
    }
    static void highScorePlanning() {
        check(Planner.linePoints(1,1)==10,"base clear uses 10*C");
        check(Planner.linePoints(2,101)==2020,"double line uses 20*C");
        check(Planner.linePoints(3,101)==6060,"triple line uses 60*C");
        check(Planner.linePoints(6,101)==30300,"six lines use 300*C");
        check(Planner.linePoints(6,1000)==300000,"uncapped extreme combo rewards");
        check(Planner.survival(0L)>Planner.survival(0x55aa55aa55aa55aaL),
            "scattered free cells are not equivalent to usable 3x3/5-long spaces");
        check(Planner.survival(0L)>Planner.survival(-1L),
            "empty board is safer than a completely blocked board");
        Game.Piece one=new Game.Piece(0,0);
        Game.Piece square=new Game.Piece(0,0,1,0,0,1,1,1);
        long cross=0x808080808080807fL;
        Planner.Result reward=Planner.best(cross,java.util.Arrays.asList(one),160,101,0);
        check(reward!=null&&reward.moves.size()==1&&reward.moves.get(0).x==7&&reward.moves.get(0).y==0,
            "at combo 100 prioritize a legitimate cross clear");
        Planner.Result all=Planner.best(0,java.util.Arrays.asList(square,square,square),180,101,0);
        check(all!=null&&all.moves.size()==3,"still plan full visible tray at very high combo");
        ScoreTracker tracker=new ScoreTracker();
        tracker.seedDisplayedCombo(100);
        check(tracker.combo()==101,"shown 100 maps to baseline internal 101");
        Game.Shape shape=new Game.Shape(0,one,180,1100,0xffaaccff);
        Game.Frame before=new Game.Frame(cross,java.util.Arrays.asList(shape),40,360,79,709,1536,1);
        tracker.expect(before,0,7,0);
        Game.Frame after=new Game.Frame(0,java.util.Arrays.asList(
            new Game.Shape(0,one,180,1100,0xffaaccff),
            new Game.Shape(1,square,320,1100,0xff00ffaa),
            new Game.Shape(2,one,560,1100,0xffffaa00)),40,360,79,709,1536,1);
        tracker.observe(after);
        check(tracker.combo()==102&&tracker.misses()==0,
            "confirmed cross clear preserves and increments a 100+ chain");
        tracker.expect(after,0,0,0);
        Game.Frame mismatch=new Game.Frame(4L,java.util.Arrays.asList(
            after.shapes.get(1),after.shapes.get(2)),40,360,79,709,1536,1);
        tracker.observe(mismatch);
        check(tracker.combo()==102&&tracker.misses()==1,
            "different valid manual placement must not erase a proven combo");
        tracker.expect(mismatch,0,0,0);
        Game.Frame impossible=new Game.Frame(-1L,java.util.Arrays.asList(
            mismatch.shapes.get(1)),40,360,79,709,1536,1);
        tracker.observe(impossible);
        check(tracker.combo()==0,"impossible board transition invalidates inferred chain");
    }
    static void planning() {
        Game.Piece one=new Game.Piece(0,0);
        long cross=0x808080808080807fL;
        Rules.Placement cleared=Rules.place(cross,one,7,0);
        check(cleared!=null && cleared.board==0 && cleared.lines==2, "row and column clear simultaneously");
        check(Rules.place(1,one,0,0)==null,"overlap never legal");
        check(Rules.place(0,one,8,0)==null,"board boundaries enforced");
        Game.Piece square=new Game.Piece(0,0,1,0,0,1,1,1);
        Planner.Result partial=Planner.best(0x55aa55aa55aa55aaL & ~1L,java.util.Arrays.asList(one,square),100);
        check(partial!=null && partial.moves.size()==1 && partial.moves.get(0).piece==0,"make legal partial move if whole hand impossible");
        Planner.Result result=Planner.best(0,java.util.Arrays.asList(square,square,square),150);
        check(result!=null && result.moves.size()==3,"plan all three pieces with bounded search");
        long board=0;
        for(Planner.Move m:result.moves) {Rules.Placement p=Rules.place(board,square,m.x,m.y);check(p!=null,"planner returns legal moves");board=p.board;}
        check(Planner.best(-1L,java.util.Arrays.asList(one),20)==null,"no illegal fallback");
        // A very tight beam budget must not drop the third legal placement.
        // The fallback is permitted to be less profitable, but not partial.
        for(int budget:new int[]{5,10,25}) {
            Planner.Result tight=Planner.best(0,java.util.Arrays.asList(square,square,square),budget,100,0);
            check(tight!=null&&tight.moves.size()==3,"complete tray under "+budget+"ms search budget");
            long simulated=0;
            boolean[] visited=new boolean[3];
            for(Planner.Move move:tight.moves) {
                check(!visited[move.piece],"fallback never repeats the same tray item");
                visited[move.piece]=true;
                Rules.Placement placed=Rules.place(simulated,square,move.x,move.y);
                check(placed!=null,"fallback placement remains legal");
                simulated=placed.board;
            }
        }

    }
    static void alignment() throws Exception {
        Game.Image before=load("empty-gold.png");Game.Frame frame=Recognizer.read(before);
        Game.Shape shape=frame.shapes.get(1);
        Game.Image after=new Game.Image(before.width,before.height,before.pixels.clone());
        float ox=241,oy=1008;
        for(int i=0;i<shape.piece.xs.length;i++) {
            int x1=Math.round(ox+shape.piece.xs[i]*frame.cell+2),y1=Math.round(oy+shape.piece.ys[i]*frame.cell+2);
            for(int y=y1;y<y1+frame.cell-4;y++)for(int x=x1;x<x1+frame.cell-4;x++)after.pixels[y*after.width+x]=shape.color;
        }
        DragAlignment.Origin found=DragAlignment.locate(after,before,frame,shape,350,1320);
        check(found!=null,"find full-size lifted piece with unknown finger offset");
        check(Math.abs(found.x-ox)<frame.cell*.12 && Math.abs(found.y-oy)<frame.cell*.12,"measure origin instead of fixed Y correction");
        check(DragAlignment.locate(before,before,frame,shape,350,1320)==null,"unchanged tray is not a picked-up block");
        Game.Frame unchanged=new Game.Frame(frame.board,frame.shapes,frame.left,frame.top,frame.cell,frame.width,frame.height,1);
        MoveVerifier verifier=new MoveVerifier(frame,0,0,0);
        check(!verifier.matches(unchanged),"gesture callback / unchanged shape count is not success");
        java.util.List<Game.Shape> remain=new java.util.ArrayList<>(frame.shapes);remain.remove(0);
        Game.Frame placed=new Game.Frame(7L,remain,frame.left,frame.top,frame.cell,frame.width,frame.height,1);
        check(verifier.matches(placed),"exact predicted board plus consumed source accepted");
        Game.Frame wrong=new Game.Frame(14L,remain,frame.left,frame.top,frame.cell,frame.width,frame.height,1);
        check(!verifier.matches(wrong),"piece landed one column away is failure");
        Game.Shape single=new Game.Shape(1,new Game.Piece(0,0),200,1200,0xffffdd44);
        Game.Frame last=new Game.Frame(0x808080808080807fL,java.util.Arrays.asList(single),frame.left,frame.top,frame.cell,frame.width,frame.height,1);
        MoveVerifier clear=new MoveVerifier(last,0,7,0);
        check(!clear.matches(new Game.Frame(0,java.util.Collections.emptyList(),frame.left,frame.top,frame.cell,frame.width,frame.height,1)),"last block waits for refreshed hand, not an empty animation");
        check(clear.matches(frame),"last block simultaneous clear accepts complete fresh hand");
        check(!clear.matches(new Game.Frame(0,frame.shapes,frame.left,frame.top+40,frame.cell,frame.width,frame.height,1)),"different geometry cannot verify stale placement");
    }
    static void variants() throws Exception {
        BufferedImage original=ImageIO.read(new File("tests/fixtures/empty-gold.png"));
        for(int width:new int[]{360,540,1080}) {
            float scale=width/709f;int dy=27;
            BufferedImage resized=new BufferedImage(width,Math.round(original.getHeight()*scale)+dy,BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics=resized.createGraphics();
            graphics.drawImage(original,0,dy,width,Math.round(original.getHeight()*scale),null);graphics.dispose();
            Game.Frame f=Recognizer.read(new Game.Image(width,resized.getHeight(),resized.getRGB(0,0,width,resized.getHeight(),null,0,width)));
            check(f!=null&&f.board==0&&f.shapes.size()==3,"scaled and translated screenshot "+width);
            check(Math.abs(f.top-(362*scale+dy))<4,"actual grid top follows image, not screen fraction "+width);
            check(f.shapes.get(1).piece.key.equals("0,0;1,0;1,1;"),"scaled L stays exact "+width);
        }
        int w=700,h=1500;int[] pixels=new int[w*h];java.util.Arrays.fill(pixels,0xff325a8c);
        for(int y=330;y<906;y++)for(int x=60;x<636;x++)pixels[y*w+x]=(x-60)%72<3||(y-330)%72<3?0xff0e172d:0xff233752;
        for(int y=330;y<909;y++)for(int x=60;x<639;x++)if(y>=906||x>=636)pixels[y*w+x]=0xff0e172d;
        // One occupied red tile, and three differently colored tray shapes.
        for(int y=330+2*72+4;y<330+3*72-3;y++)for(int x=60+4*72+4;x<60+5*72-3;x++)pixels[y*w+x]=0xffef5656;
        Game.Piece[] pieces={new Game.Piece(0,0,1,0,2,0),new Game.Piece(0,0,0,1,1,1),new Game.Piece(0,0,1,1)};
        int[] colors={0xffef5656,0xff40b7ee,0xffc56be9};
        for(int slot=0;slot<3;slot++) {
            Game.Piece p=pieces[slot];int left=156+slot*192-p.w*32/2,top=1100-p.h*32/2;
            for(int i=0;i<p.xs.length;i++)for(int y=top+p.ys[i]*32+1;y<top+(p.ys[i]+1)*32-1;y++)for(int x=left+p.xs[i]*32+1;x<left+(p.xs[i]+1)*32-1;x++)pixels[y*w+x]=colors[slot];
        }
        Game.Frame multi=Recognizer.read(new Game.Image(w,h,pixels));
        check(multi!=null&&multi.board==(1L<<20)&&multi.shapes.size()==3,"multicolor board/tray with different background: "+(multi==null?"null":multi.left+","+multi.top+" board="+Long.toHexString(multi.board)+" shapes="+multi.shapes.size()));
        for(int slot=0;slot<3;slot++)check(multi.shapes.get(slot).piece.key.equals(pieces[slot].key),"multicolor exact shape "+slot);
        for(int y=330+2*72+4;y<330+3*72-3;y++)for(int x=60+4*72+4;x<60+5*72-3;x++)pixels[y*w+x]=0xff64185f;
        Game.Frame dark=Recognizer.read(new Game.Image(w,h,pixels));
        check(dark==null||dark.board==(1L<<20),"unsupported dark occupied cells must never become empty board");
    }
    static void simulations() {
        java.util.Random random=new java.util.Random(707);
        java.util.List<Game.Piece> bag=java.util.Arrays.asList(new Game.Piece(0,0),new Game.Piece(0,0,1,0,2,0),new Game.Piece(0,0,0,1,0,2,0,3,0,4),new Game.Piece(0,0,1,0,0,1,1,1),new Game.Piece(0,0,1,0,2,0,0,1,1,1,2,1,0,2,1,2,2,2),new Game.Piece(0,0,1,1),new Game.Piece(0,0,1,0,1,1));
        int moves=0,clears=0;long worst=0;
        for(int game=0;game<8;game++) {
            long board=0;
            for(int hand=0;hand<15;hand++) {
                java.util.List<Game.Piece> pieces=new java.util.ArrayList<>();for(int i=0;i<3;i++)pieces.add(bag.get(random.nextInt(bag.size())));
                long start=System.nanoTime();Planner.Result result=Planner.best(board,pieces,50);worst=Math.max(worst,(System.nanoTime()-start)/1_000_000);
                if(result==null)break;
                boolean[] used=new boolean[3];
                for(Planner.Move move:result.moves) {
                    check(!used[move.piece],"no duplicate piece in one hand");used[move.piece]=true;
                    Rules.Placement placed=Rules.place(board,pieces.get(move.piece),move.x,move.y);
                    check(placed!=null,"all simulated moves legal");board=placed.board;moves++;clears+=placed.lines;
                }
                if(result.moves.size()<3)break;
            }
        }
        check(moves>=80&&clears>0,"seeded simulation makes progress and clears lines");
        check(worst<1200,"bounded planning does not run unbounded exhaustive search");
        System.out.println("Simulation: "+moves+" legal placements, "+clears+" lines, worst planner "+worst+"ms (50ms budget)");
    }
    static void frameReuseSafetyRegression() throws Exception {
        Game.Image image=load("empty-gold.png");
        Recognizer.Detection first=Recognizer.analyze(image);
        check(first.frame!=null,"frame reuse fixture must be a valid board");
        FrameRecognitionCache cache=new FrameRecognitionCache();
        check(cache.reuse(image,true,true,false)==null,
            "cannot reuse detection before first observation");
        cache.remember(image,first);
        check(cache.reuse(image,true,true,false)==first,
            "identical untouched captured pixels may reuse prior detection");
        check(cache.reuse(image,true,true,true)==null,
            "a NEW ImageReader capture must be reanalyzed, even with same colors");
        check(cache.reuse(image,false,true,false)==null,
            "advisor mode never reuses potentially overlay-cleaned detection");
        check(cache.reuse(image,true,false,false)==null,
            "sanitized/modified overlay pixels are never mistaken for original");
        Game.Image copy=new Game.Image(image.width,image.height,image.pixels.clone());
        check(cache.reuse(copy,true,true,false)==null,
            "identical-looking but separately captured buffer is NOT cached");
        cache.reset();
        check(cache.reuse(image,true,true,false)==null,
            "session restart invalidates cached detection");
    }
    static void pixelSamplerGeometryRegression() {
        for(int stride:new int[]{24,28})for(int maxWidth:new int[]{5,3,2,5}){
            int width=5,height=3;
            for(int variant=0;variant<2;variant++) {
                byte[] bytes=new byte[(height-1)*stride+width*4];
                for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                    int offset=y*stride+x*4;
                    bytes[offset]=(byte)(x*41+variant*31);
                    bytes[offset+1]=(byte)(y*67+x);
                    bytes[offset+2]=(byte)(x*19+y*23+variant*13);
                    bytes[offset+3]=(byte)255;
                }
                Game.Image image=PixelReader.read(java.nio.ByteBuffer.wrap(bytes),
                    width,height,stride,4,maxWidth);
                int w=Math.min(width,maxWidth);
                int h=Math.max(1,Math.round(height*w/(float)width));
                check(image.width==w&&image.height==h,
                    "sampler tracks rescaled output geometry");
                for(int yy=0;yy<h;yy++)for(int xx=0;xx<w;xx++) {
                    int sx=Math.min(width-1,(int)((xx+.5)*width/w));
                    int sy=Math.min(height-1,(int)((yy+.5)*height/h));
                    int red=(sx*41+variant*31)&255;
                    int green=(sy*67+sx)&255;
                    int blue=(sx*19+sy*23+variant*13)&255;
                    int expected=0xff000000|(red<<16)|(green<<8)|blue;
                    check(image.pixels[yy*w+xx]==expected,
                        "cached resampler preserves RGBA colors, stride and rounding");
                }
            }
        }
    }

    static void pixelBuffers() {
        // Two rows of RGBA, one padding pixel per row; final padding is absent.
        java.nio.ByteBuffer rgba=java.nio.ByteBuffer.wrap(new byte[]{(byte)255,0,0,(byte)255,0,(byte)255,0,(byte)255,99,99,99,99,0,0,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255});
        Game.Image image=PixelReader.read(rgba,2,2,12,4,720);
        check(image!=null&&image.width==2&&image.height==2,"read padded/truncated RGBA buffer");
        check(image.pixels[0]==0xffff0000&&image.pixels[1]==0xff00ff00&&image.pixels[2]==0xff0000ff&&image.pixels[3]==-1,"RGBA channels and row stride stay correct");
        boolean rejected=false;
        try {PixelReader.read(java.nio.ByteBuffer.allocate(4),2,2,12,4,720);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"truncated actual pixel data rejected before reading");
    }
    static void ambiguousFrames() throws Exception {
        Game.Image original=load("empty-gold.png");
        for(int color:new int[]{0xff644b23,0xff382315}) {
            int[] pixels=original.pixels.clone();
            for(int y=367;y<435;y++)for(int x=46;x<114;x++)pixels[y*original.width+x]=color;
            Game.Frame f=Recognizer.read(new Game.Image(original.width,original.height,pixels));
            check(f==null||(f.board&1L)!=0,"near-background or darker occupied tile never read as empty "+Integer.toHexString(color));
        }
        int[] pixels=original.pixels.clone();
        for(int y=1140;y<1210;y++)for(int x=319;x<391;x++) {
            int c=pixels[y*original.width+x];
            if(Recognizer.isTile(c)&&((c>>16)&255)>210)pixels[y*original.width+x]=0xffd6d6d6;
        }
        check(Recognizer.read(new Game.Image(original.width,original.height,pixels))==null,"unknown occupied pocket is uncertain, not absent");
        pixels=original.pixels.clone();
        for(int y=1140;y<1210;y++)for(int x=355;x<370;x++)pixels[y*original.width+x]=0xffababab;
        check(Recognizer.read(new Game.Image(original.width,original.height,pixels))==null,"occluded piece invalidates hand instead of becoming a partial shape");
        pixels=original.pixels.clone();
        for(int y=1176;y<1207;y++)for(int x=357;x<388;x++)pixels[y*709+x]=0xff38a8e8;
        check(Recognizer.read(new Game.Image(709,1536,pixels))==null,"different colored occlusion cannot erase an arm and become a smaller piece");
        pixels=original.pixels.clone();
        for(int y=1045;y<1297;y++)for(int x=41;x<669;x++)pixels[y*709+x]=0xffb87828;
        for(int y=1155;y<1190;y++)for(int x=100;x<135;x++)pixels[y*709+x]=0xff986020;
        check(Recognizer.read(new Game.Image(709,1536,pixels))==null,"low contrast visible unknown face is not an empty pocket");
        for(int cover:new int[]{0xffa0a000,0xff986020,0xffaa8810}) {
            pixels=original.pixels.clone();
            for(int y=1176;y<1207;y++)for(int x=357;x<388;x++)pixels[y*709+x]=cover;
            check(Recognizer.read(new Game.Image(709,1536,pixels))==null,"same-hue/dimmer overlay cannot become a smaller actionable shape "+Integer.toHexString(cover));
        }
    }
    static void tile(Game.Image im,float x,float y,float cell) {
        for(int yy=(int)y+1;yy<y+cell-1;yy++)for(int xx=(int)x+1;xx<x+cell-1;xx++) {
            int c=0xfffbdc4e;
            if(yy<y+cell*.12)c=0xffffed87;
            if(xx>x+cell*.86&&yy>y+cell*.12)c=0xffd7a527;
            im.pixels[yy*im.width+xx]=c;
        }
    }
    static void bridgeAlignment() throws Exception {
        Game.Image original=load("empty-gold.png");Game.Frame frame=Recognizer.read(original);Game.Shape shape=frame.shapes.get(0);
        Game.Image before=new Game.Image(original.width,original.height,original.pixels.clone());
        for(int x:new int[]{0,1,2,6,7})tile(before,frame.left+x*frame.cell,frame.top,frame.cell);
        Game.Image after=new Game.Image(original.width,original.height,before.pixels.clone());
        for(int x:new int[]{3,4,5})tile(after,frame.left+x*frame.cell,frame.top,frame.cell);
        float target=frame.left+3*frame.cell;
        DragAlignment.Origin match=DragAlignment.locateNear(after,before,frame,shape,target,frame.top);
        check(match!=null&&Math.abs(match.x-target)<frame.cell*.12&&Math.abs(match.y-frame.top)<frame.cell*.12,
            "joining same-color old tiles must not shift held-piece origin by whole cells");
    }
    static void recognition() throws Exception {
        Game.Frame f = Recognizer.read(load("empty-gold.png"));
        check(f != null, "real screenshot: must detect grid");
        check(f.board == 0L, "empty gold board must be empty");
        check(Math.abs(f.left-41)<3 && Math.abs(f.top-362)<3 && Math.abs(f.cell-78.5)<1,
            "grid comes from pixels, with correct origin and pitch");
        check(f.shapes.size()==3, "all three tray pockets");
        check(f.shapes.get(0).piece.key.equals("0,0;1,0;2,0;"), "three cells, not four");
        check(f.shapes.get(1).piece.key.equals("0,0;1,0;1,1;"), "top-right L orientation");
        check(f.shapes.get(2).piece.key.equals("1,0;0,1;1,1;"), "bottom-left L orientation");
        for (Game.Shape s:f.shapes) check(Recognizer.isTile(load("empty-gold.png").at(s.pickX,s.pickY)),
            "pickup must hit an occupied tile, never an L bounding-box hole");
        Game.Frame d=Recognizer.read(load("diagonal-gold.png"));
        check(d!=null && d.shapes.size()==3, "recognize second real screenshot");
        check(d.shapes.get(0).piece.key.equals("0,0;0,1;0,2;0,3;"), "four tall cells");
        check(d.shapes.get(1).piece.key.equals("0,0;1,1;"), "keep legitimate diagonal piece");
        check(Recognizer.read(new Game.Image(709,1536,new int[709*1536]))==null,
            "black/locked/unrelated screen rejected");
    }
    static void cachedGridRecognitionRegression() throws Exception {
        // Fast geometry reuse must never make old blocks/board actionable.
        for(String name:new String[]{"empty-gold.png","diagonal-gold.png","purple-gold-frames.png"}) {
            Game.Image image=load(name);
            Recognizer.Detection cold=Recognizer.analyze(image);
            check(cold.frame!=null,"cold grid for "+name);
            Recognizer.Detection cached=Recognizer.analyze(image,cold.frame);
            check(cached.frame!=null,"cached grid for "+name);
            check(cached.frame.signature().equals(cold.frame.signature()),
                "hint preserves exact board and tray "+name);
            check(Math.abs(cached.frame.left-cold.frame.left)<.01f &&
                Math.abs(cached.frame.top-cold.frame.top)<.01f &&
                Math.abs(cached.frame.cell-cold.frame.cell)<.01f,
                "cached geometry matches cold detection "+name);
        }
        Game.Image first=load("empty-gold.png");
        Game.Frame reference=Recognizer.analyze(first).frame;
        Game.Image changed=load("diagonal-gold.png");
        Recognizer.Detection refreshed=Recognizer.analyze(changed,reference);
        Recognizer.Detection cold=Recognizer.analyze(changed);
        check(refreshed.frame!=null && cold.frame!=null &&
            refreshed.frame.signature().equals(cold.frame.signature()),
            "changed hand must be re-read while reusing grid");
        Game.Frame invalid=new Game.Frame(reference.board,reference.shapes,
            0f,0f,reference.cell,reference.width,reference.height,reference.confidence);
        Recognizer.Detection fallback=Recognizer.analyze(first,invalid);
        check(fallback.frame!=null && fallback.frame.signature().equals(reference.signature()) &&
            Math.abs(fallback.frame.left-reference.left)<.01f,
            "bad geometry must trigger complete grid discovery");
        Game.Image black=new Game.Image(first.width,first.height,new int[first.pixels.length]);
        check(Recognizer.analyze(black,reference).issue==Recognizer.Issue.BLACK_FRAME,
            "blank capture cannot become a valid frame through stale hint");
    }

    static void diagnostics() throws Exception {
        Game.Image original=load("empty-gold.png");
        Recognizer.Detection valid=Recognizer.analyze(original);
        check(valid.issue==Recognizer.Issue.NONE&&valid.frame!=null&&valid.frame.shapes.size()==3,"valid image remains actionable through diagnostics");
        Recognizer.Detection black=Recognizer.analyze(new Game.Image(709,1536,new int[709*1536]));
        check(black.frame==null&&black.issue==Recognizer.Issue.BLACK_FRAME,"black capture is distinguished from grid/tray failure");
        int[] pixels=original.pixels.clone();
        for(int y=1140;y<1210;y++)for(int x=319;x<391;x++) {
            int c=pixels[y*original.width+x];
            if(Recognizer.isTile(c)&&((c>>16)&255)>210)pixels[y*original.width+x]=0xffd6d6d6;
        }
        Recognizer.Detection tray=Recognizer.analyze(new Game.Image(709,1536,pixels));
        check(tray.frame==null&&tray.issue==Recognizer.Issue.TRAY&&tray.slot==1,"visible unknown second block is reported as tray failure, not missing board");
        pixels=original.pixels.clone();
        for(int y=367;y<435;y++)for(int x=46;x<114;x++)pixels[y*709+x]=0xff555555;
        Recognizer.Detection board=Recognizer.analyze(new Game.Image(709,1536,pixels));
        check(board.frame==null&&board.issue==Recognizer.Issue.BOARD_CELL&&board.row==0&&board.column==0,"uncertain board cell is located and never passed to planner");
    }
    static void darkPalette() throws Exception {
        Game.Image original=load("empty-gold.png");
        Game.Frame geometry=Recognizer.read(original);
        for(int occupied:new int[]{0xff502040,0xff602050,0xff603010}) {
            int[] pixels=original.pixels.clone();
            for(int i=0;i<8;i++)for(int y=(int)(geometry.top+i*geometry.cell+4);y<geometry.top+(i+1)*geometry.cell-4;y++)
                for(int x=(int)(geometry.left+i*geometry.cell+4);x<geometry.left+(i+1)*geometry.cell-4;x++)pixels[y*709+x]=occupied;
            Game.Frame read=Recognizer.read(new Game.Image(709,1536,pixels));
            check(read==null||read.board==0x8040201008040201L,"dark occupied reference must never invert empty/occupied cells "+Integer.toHexString(occupied)+(read==null?" REJECTED":" BOARD="+Long.toHexString(read.board)+" GEO="+read.left+","+read.top+","+read.cell+" P0="+Integer.toHexString(pixels[(int)(read.top+read.cell*.5f)*709+(int)(read.left+read.cell*.5f)])+" P1="+Integer.toHexString(pixels[(int)(read.top+read.cell*.5f)*709+(int)(read.left+read.cell*1.5f)])));
        }
        for(int darkColor:new int[]{0xff803080,0xff266c8a,0xff8e3828}) {
            int[] pixels=original.pixels.clone();
            // Same measured screenshot geometry and beveled faces, darker colored material.
            for(int y=1120;y<1220;y++)for(int x=80;x<620;x++) {
                int c=pixels[y*709+x];
                int red=c>>16&255,green=c>>8&255,blue=c&255;
                if(Math.abs(red-184)+Math.abs(green-120)+Math.abs(blue-40)>100) {
                    float shade=Math.max(red,Math.max(green,blue))/255f;
                    pixels[y*709+x]=0xff000000|Math.round((darkColor>>16&255)*shade)<<16|Math.round((darkColor>>8&255)*shade)<<8|Math.round((darkColor&255)*shade);
                }
            }
            for(int y=367;y<435;y++)for(int x=46;x<114;x++)pixels[y*709+x]=darkColor;
            Recognizer.Detection result=Recognizer.analyze(new Game.Image(709,1536,pixels));
            check(result.frame!=null,"dark colored cells stay readable: "+Integer.toHexString(darkColor)+" "+result.code());
            check(result.frame.board==1L,"dark occupied cell is not empty: "+Integer.toHexString(darkColor));
            check(result.frame.shapes.size()==3&&result.frame.shapes.get(0).piece.key.equals("0,0;1,0;2,0;")&&
                result.frame.shapes.get(1).piece.key.equals("0,0;1,0;1,1;")&&
                result.frame.shapes.get(2).piece.key.equals("1,0;0,1;1,1;"),"dark tray preserves the exact three shapes");
            for(Game.Shape shape:result.frame.shapes)check(result.frame!=null&&Recognizer.distance(new Game.Image(709,1536,pixels).at(shape.pickX,shape.pickY),darkColor)<30,"dark pickup hits actual material");
        }
    }
    static void purpleFrames() throws Exception {
        Recognizer.Detection result=Recognizer.analyze(load("purple-gold-frames.png"));
        check(result.frame!=null,"actual purple theme: gold frames are decoration, not a foreign-color occlusion: "+result.code());
        Game.Frame f=result.frame;
        check(f.board==0x0c606a2f32eccdc8L,"actual multicolor occupied board matches manually labeled cells");
        check(f.shapes.size()==3,"actual purple tray has three pieces");
        check(f.shapes.get(0).piece.key.equals("0,0;1,0;"),"green horizontal pair");
        check(f.shapes.get(1).piece.key.equals("1,0;0,1;1,1;0,2;"),"red staggered four-cell piece");
        check(f.shapes.get(2).piece.key.equals("1,0;0,1;1,1;2,1;"),"orange T orientation");
        check(Planner.best(f.board,f.shapes.stream().map(shape->shape.piece).collect(java.util.stream.Collectors.toList()),180)!=null,"recognized real hand proceeds to a legal plan");
    }
    static void mergedOuterFaces() {
        int w=700,h=1500;
        for(boolean rectangle:new boolean[]{true,false}) {
            int[] pixels=new int[w*h];java.util.Arrays.fill(pixels,0xff5b3e98);
            int cols=rectangle?4:3,rows=rectangle?2:3;
            for(int row=0;row<rows;row++)for(int col=0;col<cols;col++) {
                int inset=!rectangle&&row==2?4:1;
                for(int y=1000+row*32+inset;y<1000+(row+1)*32-inset;y++)
                    for(int x=110+col*32+inset;x<110+(col+1)*32-inset;x++)pixels[y*w+x]=0xff14cc46;
            }
            if(rectangle)for(int y=1015;y<=1048;y++)pixels[y*w+221]=0xff14cc46;
            else for(int x=114;x<202;x++)pixels[1080*w+x]=0xff14cc46;
            for(int y=330;y<909;y++)for(int x=60;x<639;x++)pixels[y*w+x]=(x-60)%72<3||(y-330)%72<3||y>=906||x>=636?0xff0e172d:0xff233752;
            Game.Frame frame=Recognizer.read(new Game.Image(w,h,pixels));
            String expected=rectangle?"0,0;1,0;2,0;3,0;0,1;1,1;2,1;3,1;":"0,0;1,0;2,0;0,1;1,1;2,1;0,2;1,2;2,2;";
            check(frame!=null&&frame.shapes.size()==1&&frame.shapes.get(0).piece.key.equals(expected),"merged outer faces must not disappear: "+expected);
        }
    }
    static Game.Image lifted(Game.Image before,Game.Frame f,Game.Shape shape,float ox,float oy) {
        Game.Image im=new Game.Image(before.width,before.height,before.pixels.clone());
        for(int i=0;i<shape.piece.xs.length;i++) {
            float left=ox+shape.piece.xs[i]*f.cell,top=oy+shape.piece.ys[i]*f.cell;
            for(int y=(int)top+1;y<top+f.cell-1;y++)for(int x=(int)left+1;x<left+f.cell-1;x++) {
                if(x<0||x>=im.width||y<0||y>=im.height)continue;
                boolean trim=x<left+f.cell*.12||x>left+f.cell*.88||y<top+f.cell*.12||y>top+f.cell*.88;
                im.pixels[y*im.width+x]=trim?0xffb58a45:shape.color;
            }
        }
        return im;
    }
    static void highLift() throws Exception {
        Game.Image before=load("purple-empty-red.png");Game.Frame f=Recognizer.read(before);
        check(f!=null&&f.board==0&&f.shapes.size()==3,"new actual screenshot remains fully recognized");
        Game.Shape shape=f.shapes.get(0);check(shape.piece.key.equals("0,0;0,1;0,2;"),"literal vertical3 in latest screenshot");
        float fx=f.width*.5f,fy=Math.min(f.height-f.cell*.7f,f.top+8*f.cell+shape.piece.h*f.cell*.5f+2*f.cell);
        for(float lift:new float[]{1.85f,3.4f,4.2f}) {
            float ox=fx-f.cell*.5f,oy=fy-lift*f.cell-1.5f*f.cell;
            DragAlignment.Origin o=DragAlignment.locate(lifted(before,f,shape,ox,oy),before,f,shape,fx,fy);
            check(o!=null&&Math.abs(o.x-ox)<f.cell*.13&&Math.abs(o.y-oy)<f.cell*.13,"measure held origin with "+lift+"cell lift, including board overlap");
        }
        check(DragAlignment.locate(before,before,f,shape,fx,fy)==null,"no pickup is not a held block despite unchanged red tray");
        Game.Image gold=load("empty-gold.png");Game.Frame gf=Recognizer.read(gold);Game.Shape bar=gf.shapes.get(0);
        Game.Image oldBoard=new Game.Image(gold.width,gold.height,gold.pixels.clone());
        float x=230,y=gf.top+5*gf.cell;
        tile(oldBoard,x+2*gf.cell,y,gf.cell);
        Game.Image changed=new Game.Image(gold.width,gold.height,oldBoard.pixels.clone());
        tile(changed,x,y,gf.cell);tile(changed,x+gf.cell,y,gf.cell);
        check(DragAlignment.locate(changed,oldBoard,gf,bar,350,1320)==null,"staging search cannot borrow unchanged old tile to complete a new piece");
        Game.Shape ell=gf.shapes.get(1);
        Game.Image one=lifted(gold,gf,ell,241,1008);
        Game.Image two=lifted(one,gf,ell,241,gf.top+gf.cell);
        check(DragAlignment.locate(two,gold,gf,ell,350,1320)==null,"distinct equally plausible held/preview shapes require movement evidence, not first match");
        float delta=gf.cell*.7f;
        Game.Image moving=lifted(gold,gf,ell,241+delta,1008);
        Game.Image withStatic=lifted(moving,gf,ell,241,gf.top+gf.cell);
        DragAlignment.Origin tracked=DragAlignment.locateMoving(withStatic,gold,two,gf,ell,350+delta,1320,delta,0);
        check(tracked!=null&&Math.abs(tracked.x-(241+delta))<gf.cell*.13&&Math.abs(tracked.y-1008)<gf.cell*.13,"motion evidence follows held block and rejects static duplicate");
        // Captured v1.0.0 NO_HELD_MATCH diagnostic: a commanded .65-cell
        // probe can move a real picked piece a full displayed cell.
        Game.Image snapPrev=lifted(gold,gf,ell,300,1008);
        Game.Image snapNow=lifted(gold,gf,ell,300-gf.cell,1008);
        DragAlignment.Origin quantized=DragAlignment.locateMoving(snapNow,gold,snapPrev,gf,ell,
            400-gf.cell*.65f,1320,-gf.cell*.65f,0);
        check(quantized!=null && Math.abs(quantized.x-(300-gf.cell))<gf.cell*.14f,
            "a whole-cell-rendered block must match the same-direction subcell probe");
        check(DragAlignment.locateMoving(snapPrev,gold,snapPrev,gf,ell,
            400-gf.cell*.65f,1320,-gf.cell*.65f,0)==null,
            "a stationary tray or preview can never count as held motion");
        check(DragAlignment.locateMoving(snapNow,gold,snapPrev,gf,ell,
            400-gf.cell*.65f,1320,+gf.cell*.65f,0)==null,
            "movement opposite to the requested probe cannot release a block");
        Game.Image withSnapped=lifted(moving,gf,ell,241+gf.cell,gf.top+gf.cell);
        tracked=DragAlignment.locateMoving(withSnapped,gold,two,gf,ell,350+delta,1320,delta,0);
        check(tracked!=null&&Math.abs(tracked.y-1008)<gf.cell*.13,"one-cell snapped preview is not continuous finger motion");
        Game.Image twoMoving=lifted(moving,gf,ell,241+delta,gf.top+gf.cell);
        check(DragAlignment.locateMoving(twoMoving,gold,two,gf,ell,350+delta,1320,delta,0)==null,"two equally moving candidates remain ambiguous");
    }
    /** Regression from uploaded v1.3.1 ZIP metadata:
     * ALIGN, NO_HELD_MATCH, 2x2 purple, goal near (440,524), actual held
     * near (523,273), upper ~16.7% of the tile sampling behind our HUD.
     * Use a synthetic frame, never commit the user's private screenshot.
     */
    /** v1.3.2 user's CORRECTION_LIMIT: legal held 2x2 cells covered exactly
     * the destination (col 5,row 2), but artwork origin stayed 0.22 cell high
     * after several correction gestures. Only target-cell evidence can break
     * that futile correction loop.
     */
    static void snappedTargetPreviewRegression() throws Exception {
        Game.Image empty=load("empty-gold.png");
        Game.Frame frame=Recognizer.read(empty);
        check(frame!=null,"real fixture supplies board geometry for snapped preview");
        int tx=5,ty=2;
        Game.Piece square=new Game.Piece(0,0,1,0,0,1,1,1);
        Game.Shape held=new Game.Shape(2,square,551.5f,1174,0xff8451d6);
        check(Rules.place(frame.board,square,tx,ty)!=null,"target is legal for 2x2 shape");
        float left=frame.left+tx*frame.cell;
        float top=frame.top+ty*frame.cell;
        Game.Image snapped=lifted(empty,frame,held,left+frame.cell*.11f,top-frame.cell*.22f);
        check(DragAlignment.targetCellPreview(snapped,empty,frame,held,tx,ty),
            "slightly displaced purple 2x2 preview still covers all four legal target cells");
        check(!DragAlignment.targetCellPreview(empty,empty,frame,held,tx,ty),
            "unchanged static board cannot authorize a release");
        Game.Image oneCellWrong=lifted(empty,frame,held,left+frame.cell,top);
        check(!DragAlignment.targetCellPreview(oneCellWrong,empty,frame,held,tx,ty),
            "a block placed one entire column away cannot authorize release");
        Game.Image halfCellWrong=lifted(empty,frame,held,left+frame.cell*.55f,top);
        check(!DragAlignment.targetCellPreview(halfCellWrong,empty,frame,held,tx,ty),
            "a block substantially beyond the correct snap basin must fail interior probes");
        Game.Shape notPurple=new Game.Shape(2,square,551.5f,1174,0xffff4400);
        check(!DragAlignment.targetCellPreview(snapped,empty,frame,notPurple,tx,ty),
            "different-colored transient artwork cannot authorize release");
    }

    static void alignRecoveryBehindOverlay() throws Exception {
        Game.Image baseline=load("empty-gold.png");
        Game.Frame board=Recognizer.read(baseline);
        check(board!=null,"real board geometry for occlusion recovery");
        Game.Piece square=new Game.Piece(0,0,1,0,0,1,1,1);
        Game.Shape selected=new Game.Shape(2,square,550,1174,0xff8451d6);
        float heldX=523,heldY=273,fingerX=573,fingerY=733;
        Game.Image moved=lifted(baseline,board,selected,heldX,heldY);
        // Simulate the movable HUD in front of the top 22%-40% sampling
        // strip, but never over any of the four occupied center pixels.
        int[] painted=moved.pixels.clone();
        for(int y=273;y<=304;y++)
            for(int x=523;x<=Math.min(669,523+2*(int)board.cell);x++)
                painted[y*moved.width+x]=0xff24293d;
        Game.Image occluded=new Game.Image(moved.width,moved.height,painted);
        float targetX=board.left+5*board.cell,targetY=board.top+2*board.cell;
        check(DragAlignment.locateNear(occluded,baseline,board,selected,targetX,targetY)==null,
            "strict local target check must reject an off-board, HUD-obscured held object");
        DragAlignment.Origin recovered=DragAlignment.locate(
            occluded,baseline,board,selected,fingerX,fingerY);
        check(recovered!=null,
            "newly rendered block with every changed center and 83% visible color must be recovered");
        check(Math.abs(recovered.x-heldX)<board.cell*.15f &&
              Math.abs(recovered.y-heldY)<board.cell*.15f,
            "HUD-aware re-acquisition returns actual held origin, not target or old tray");
        check(DragAlignment.locate(baseline,baseline,board,selected,fingerX,fingerY)==null,
            "occlusion allowance cannot reinterpret an unpicked stationary block as a held object");
    }
    static void diagnosticArchive() throws Exception {
        Game.Image before=new Game.Image(2,1,new int[]{0xffff0000,0xff00ff00});
        Game.Image held=new Game.Image(2,1,new int[]{0xff0000ff,0xffffffff});
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        new FailureTrace(before,held,"{\"phase\":\"PICKED\"}").writeZip(bytes,(im,out)-> {
            BufferedImage png=new BufferedImage(im.width,im.height,BufferedImage.TYPE_INT_ARGB);
            png.setRGB(0,0,im.width,im.height,im.pixels,0,im.width);ImageIO.write(png,"png",out);
        });
        java.util.Map<String,byte[]> entries=new java.util.HashMap<>();
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            java.util.zip.ZipEntry e;while((e=zip.getNextEntry())!=null)entries.put(e.getName(),zip.readAllBytes());
        }
        check(entries.size()==3&&entries.containsKey("before.png")&&entries.containsKey("held.png"),"diagnostic zip has both distinct frames and metadata");
        check(new String(entries.get("metadata.json"),java.nio.charset.StandardCharsets.UTF_8).equals("{\"phase\":\"PICKED\"}"),"diagnostic stage remains associated with frozen images");
        check(ImageIO.read(new java.io.ByteArrayInputStream(entries.get("before.png"))).getRGB(0,0)==0xffff0000,"before image is real decoded pre-action PNG");
        check(ImageIO.read(new java.io.ByteArrayInputStream(entries.get("held.png"))).getRGB(0,0)==0xff0000ff,"held image differs from before and survives encoding");
    }

    /** Palette/geometry sampled from the reported 709x1536 blue game screenshot. */
    static void blueThemeGeometry() {
        int w=709,h=1536,p=79,left=40,top=360;
        int[] pix=new int[w*h];java.util.Arrays.fill(pix,0xff435d98);
        for(int y=top;y<top+8*p+3;y++)for(int x=left;x<left+8*p;x++) {
            boolean boundary=(x-left)%p<3||(y-top)%p<3;
            pix[y*w+x]=boundary?0xff1c2648:0xff232b54;
        }
        int[][] cells={
            {0,6,0x885be5},
            {3,1,0x885be5},{3,2,0x5089d2},{3,3,0x64bc56},{3,4,0x64bc56},
            {3,6,0x64bc56},{3,7,0x64bc56},
            {4,1,0x885be5},{4,2,0x885be5},{4,3,0x885be5},{4,4,0x885be5},
            {5,0,0x885be5},{5,1,0x885be5},{5,2,0x885be5},{5,3,0x885be5},{5,4,0x885be5},
            {5,6,0xe0ac45},{6,3,0x885be5},{6,4,0x885be5},{6,6,0xe0ac45},
            {7,2,0x5089d2},{7,4,0x5089d2},{7,6,0xe0ac45}
        };
        long expected=0;
        for(int[] tile:cells) {
            int row=tile[0],col=tile[1],color=0xff000000|tile[2];
            expected|=1L<<(8*row+col);
            for(int y=top+row*p+5;y<top+(row+1)*p-5;y++)
                for(int x=left+col*p+5;x<left+(col+1)*p-5;x++)pix[y*w+x]=color;
        }
        // Only third tray slot remains: 2x2 sky-blue square.
        for(int row=0;row<2;row++)for(int col=0;col<2;col++)
            for(int y=1140+row*35+2;y<1140+(row+1)*35-2;y++)
                for(int x=527+col*35+2;x<527+(col+1)*35-2;x++)pix[y*w+x]=0xff5089d2;
        Recognizer.Detection d=Recognizer.analyze(new Game.Image(w,h,pix));
        check(d.frame!=null,"blue geometry must read: "+d.code());
        Game.Frame frame=d.frame;
        check(Math.abs(frame.left-left)<4&&Math.abs(frame.top-top)<4&&Math.abs(frame.cell-p)<1,
            "no shifted first-row columns on blue background");
        check(frame.board==expected,"multicolor blue board matches all 23 occupied cells");
        check(frame.shapes.size()==1&&frame.shapes.get(0).slot==2&&
            frame.shapes.get(0).piece.key.equals("0,0;1,0;0,1;1,1;"),
            "remaining blue 2x2 piece reads without missing or phantom blocks");
    }

    /** Warm brown real-world palette with pale diagonal and gold vertical pieces. */
    static void brownThemeGeometry() {
        int w=709,h=1536,p=79,l=40,t=360;
        int[] pixels=new int[w*h];java.util.Arrays.fill(pixels,0xffb17954);
        for(int y=t;y<t+8*p+3;y++)for(int x=l;x<l+8*p;x++)
            pixels[y*w+x]=((x-l)%p<3||(y-t)%p<3)?0xff653a2a:0xff77492f;
        // First: vertical gold 3; second: horizontal crimson 2;
        // third: diagonal pale two-cell pair.
        int[][][] coords={{{0,0},{0,1},{0,2}},{{0,0},{1,0}},{{1,0},{0,1}}};
        int[] startsX={130,319,527},startsY={1122,1157,1140},colors={0xffd9b867,0xffbb3945,0xffe0dcbf};
        for(int slot=0;slot<3;slot++)
            for(int[] tile:coords[slot])
                for(int y=startsY[slot]+tile[1]*35+2;y<startsY[slot]+(tile[1]+1)*35-2;y++)
                    for(int x=startsX[slot]+tile[0]*35+2;x<startsX[slot]+(tile[0]+1)*35-2;x++)
                        pixels[y*w+x]=colors[slot];
        Recognizer.Detection det=Recognizer.analyze(new Game.Image(w,h,pixels));
        check(det.frame!=null,"brown theme and neutral ivory tiles should be readable: "+det.code());
        check(det.frame.board==0&&det.frame.shapes.size()==3,"brown board empty and all tray pieces present");
        check(det.frame.shapes.get(0).piece.key.equals("0,0;0,1;0,2;"),"gold vertical piece");
        check(det.frame.shapes.get(1).piece.key.equals("0,0;1,0;"),"red pair");
        check(det.frame.shapes.get(2).piece.key.equals("1,0;0,1;"),"pale diagonal pair");
    }

    static void guideOverlaySanitization() {
        int w=160,h=160,background=0xff233451;
        int[] pixels=new int[w*h];java.util.Arrays.fill(pixels,background);
        // The drawn guide ring is an accessibility overlay that appears
        // in MediaProjection screenshots but must not alter recognition.
        int ink=0xff00ffaa,box=30,size=60;
        int l=box+(int)(size*.12f),r=box+(int)(size*.88f);
        int t=box+(int)(size*.12f),b=box+(int)(size*.88f);
        for(int y=t;y<=b;y++)for(int x=l;x<=r;x++)
            if(x<=l+2||x>=r-2||y<=t+2||y>=b-2)pixels[y*w+x]=ink;
        Game.Image raw=new Game.Image(w,h,pixels);
        Game.Image clean=GuideMask.strip(raw,box,box,size,w,h,new int[]{0,0});
        check(clean!=raw,"guide pixels are recognized and filtered");
        check(clean.at(l+1,t+1)==background,"guide outline cannot be interpreted as tile color");
        check(raw.at(l+1,t+1)==ink,"raw screenshot remains untouched");
        check(clean.at(60,60)==background,"interior probe color is retained");
        check(GuideMask.strip(raw,box,box,size,w,h,new int[]{1,1})==raw,
            "unrelated positions cannot be rewritten");
        check(GuideMask.strip(raw,box,box,size,w,h,new int[0])==raw,
            "no guide does not copy entire screenshot");
    }

    static void comboCaptionDetection() {
        check(ComboText.displayed("COMBO 43")==43,"explicit English combo caption");
        check(ComboText.displayed("Combo x 100")==100,"multiplication-prefix caption");
        check(ComboText.displayed("Combo × 255")==255,"Unicode multiplier caption");
        check(ComboText.displayed("Combo: 1,234")==1234,"comma grouping is not truncated");
        check(ComboText.displayed("COMBO 99\\n+440")==99,"ignore trailing scored points");
        check(ComboText.displayed("86964\\n86964")==-1,"large score is never a combo");
        check(ComboText.displayed("👑 86964\\nCOMBO\\n86964")==-1,
            "label without a nearby numeric token is not enough to trust a score");
        check(ComboText.displayed("COMBO")==-1,"numberless combo cannot anchor chain");
        check(ComboText.displayed("COMBO 0")==-1,"zero caption is not reliable");
        check(ComboText.displayed("COMBO 100001")==-1,"out-of-range caption ignored");
        check(ComboText.displayed("COMBO 12 COMBO 20")==-1,"disagreeing captions ignored");
        ScoreTracker inferred=new ScoreTracker();
        inferred.observeDisplayedCombo(100);
        check(inferred.combo()==101,"explicit displayed 100 seeds baseline internal 101");
        inferred.observeDisplayedCombo(-1);
        check(inferred.combo()==101,"failed OCR does not reset known chain");
    }
    /** Uploaded v1.3.3 failure, T shape. A 720x1560 image showed the
     * sprite at (326,35) when the finger was near (346,574), while the
     * floating status panel covered three of four occupied cell centers.
     * The safe fix is to dock the panel BELOW the game instead of lowering
     * the full-shape detection threshold to a single visible cell.
     */
    static void statusOverlayOcclusionRegression() throws Exception {
        Game.Image before=load("empty-gold.png");
        Game.Frame frame=Recognizer.read(before);
        check(frame!=null,"board geometry for screenshot scale 720x1560");
        Game.Piece tee=new Game.Piece(0,0,1,0,2,0,1,1);
        Game.Shape held=new Game.Shape(0,tee,149.5f,1173.5f,0xfff3c846);
        Game.Image unobscured=lifted(before,frame,held,326,35);
        DragAlignment.Origin found=DragAlignment.locate(unobscured,before,frame,held,346,574);
        check(found!=null && Math.abs(found.y-35)<frame.cell*.16f,
            "yellow T held above board is trackable without covering HUD");
        int[] blocked=unobscured.pixels.clone();
        for(int y=69;y<294;y++)for(int x=190;x<483;x++)
            blocked[y*unobscured.width+x]=0xff3d3d32;
        Game.Image obscured=new Game.Image(unobscured.width,unobscured.height,blocked);
        check(DragAlignment.locate(obscured,before,frame,held,346,574)==null,
            "opaque floating overlay covering 3/4 centers invalidates safe visual tracking");
    }
    static void boundedDraggedPointerRegression() {
        float cell=79.125f;
        check(Math.abs(DragStep.axis(-662,cell))<=cell*2.00001f,
            "large target delta must not move pointer 8 cells in one stroke");
        check(Math.abs(DragStep.axis(+662,cell))<=cell*2.00001f,
            "drag cap holds in either direction");
        check(DragStep.axis(-662,cell)<0 && DragStep.axis(+662,cell)>0,
            "drag correction preserves the direction of visual error");
        check(Math.abs(DragStep.axis(12,cell)-7.44f)<.01f,
            "near-target motion is damped for grid-snap stability");
        check(DragStep.axis(0,cell)==0,"zero offset cannot trigger a jump");
        boolean rejected=false;
        try {DragStep.axis(Float.NaN,cell);}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"non-finite tracking geometry must stop instead of injecting a touch");
    }
    /**
     * User v1.3.4 diagnostic: 720x1560, empty board, purple vertical 2-cell
     * sprite at x=511..587 then x=433..509, with unchanged y=1043..1199.
     * Previous finger x=416, latest finger x=364, requested delta=-52.
     * The original tracker scanned only up to old fingerX+cell=496 and
     * rejected the real x=511 sprite before testing observed motion.
     * Synthetic frames reproduce the exact geometry without publishing
     * private diagnostic screenshots in the repository.
     */
    static void rightOfFingerPickupRegression() throws Exception {
        // The existing screenshot fixtures use a different resolution.
        // Use the exact 720x1560 metadata from this uploaded diagnostic,
        // without committing the user's private before/probe/held images.
        Game.Frame frame=new Game.Frame(0L,java.util.Collections.emptyList(),
            41f,368f,79.875f,720,1560,1f);
        float cell=frame.cell;
        Game.Piece vertical2=new Game.Piece(0,0,0,1);
        Game.Shape purple=new Game.Shape(0,vertical2,149.5f,1174f,0xffa65be1);
        int[] pixels=new int[frame.width*frame.height];
        java.util.Arrays.fill(pixels,0xff243353);
        Game.Image baseline=new Game.Image(frame.width,frame.height,pixels);
        Game.Image old=lifted(baseline,frame,purple,511,1043);
        Game.Image latest=lifted(baseline,frame,purple,433,1043);
        float oldFingerX=416f,newFingerX=364f,fingerY=1246.625f;
        check(511>oldFingerX+cell,
            "prior real sprite was beyond the historical one-cell scan window");
        DragAlignment.Origin tracked=DragAlignment.locateMoving(
            latest,baseline,old,frame,purple,newFingerX,fingerY,-52,0);
        check(tracked!=null&&Math.abs(tracked.x-433)<cell*.16f &&
            Math.abs(tracked.y-1043)<cell*.16f,
            "grid-quantized 78px purple held movement must match 52px left probe");
        check(DragAlignment.locateMoving(old,baseline,old,frame,purple,
            newFingerX,fingerY,-52,0)==null,
            "stationary purple sprite cannot masquerade as held motion");
        check(DragAlignment.locateMoving(latest,baseline,old,frame,purple,
            newFingerX,fingerY,+52,0)==null,
            "a block moving opposite the commanded probe cannot be accepted");
        check(DragAlignment.locate(baseline,baseline,frame,purple,newFingerX,fingerY)==null,
            "expanded window must not mistake an unchanged board for a picked piece");
    }

    /**
     * Uploaded v1.3.5 diagnostics: the picked blue 1x5 sprite moves
     * from x~513 to x~435 after a -52px probe (actual ~-78px).
     * The lower two blue cell centers are hidden by the fixed bottom HUD
     * x=419..709,y=1326..1548 in BOTH held screenshots.
     *
     * Never relax the all-centers visual proof to accept a partly hidden
     * five-cell sprite. Instead the Android controller must hide the HUD
     * before initiating automatic input (verified by androidTest + CI).
     */
    static void verticalFiveBottomOverlayRegression() {
        int width=720,height=1560;float cell=80.125f;
        int[] pixels=new int[width*height];
        java.util.Arrays.fill(pixels,0xff243353);
        Game.Image baseline=new Game.Image(width,height,pixels);
        Game.Frame frame=new Game.Frame(0L,java.util.Collections.emptyList(),
            40f,364f,cell,width,height,1f);
        Game.Piece five=new Game.Piece(0,0,0,1,0,2,0,3,0,4);
        Game.Shape held=new Game.Shape(0,five,147,1192.5f,0xff3a5dde);
        Game.Image right=lifted(baseline,frame,held,513,1074);
        Game.Image left=lifted(baseline,frame,held,435,1074);
        DragAlignment.Origin tracked=DragAlignment.locateMoving(
            left,baseline,right,frame,held,364,1365,-52,0);
        check(tracked!=null && Math.abs(tracked.x-435)<cell*.15f,
            "an unoccluded 5-cell blue held block is trackable after grid-snapped motion");
        int[] rightPixels=right.pixels.clone(),leftPixels=left.pixels.clone();
        for(int y=1326;y<1548;y++)for(int x=419;x<709;x++) {
            rightPixels[y*width+x]=0xff2b3344;
            leftPixels[y*width+x]=0xff2b3344;
        }
        Game.Image rightBlocked=new Game.Image(width,height,rightPixels);
        Game.Image leftBlocked=new Game.Image(width,height,leftPixels);
        check(DragAlignment.locateMoving(leftBlocked,baseline,rightBlocked,
            frame,held,364,1365,-52,0)==null,
            "a bottom overlay hiding two occupied centers must not be accepted as a valid held shape");
        check(DragAlignment.locateMoving(left,baseline,left,frame,held,364,1365,-52,0)==null,
            "removing HUD cannot turn a stationary five-cell shape into real movement");
    }

    /**
     * User v1.3.6 metadata: picked red L, 720x1560, finger (364,1285),
     * last probe commanded -52px. The chronological screenshot order is
     * before.png -> probe.png -> held.png (not filename order).
     * The red L actually moved LEFT x=340 -> x=262 (-78px).
     *
     * It overlaps the original small red tray piece at x~361..395,
     * y~1139..1244, so two occupied cell CENTER samples have barely
     * changed against before.png. All-center novelty rejects that otherwise
     * valid movement. Permit overlap ONLY on a directed reverse retry.
     */
    static void thumbnailOverlapPickedMotionRegression() {
        int width=720,height=1560;
        float cell=80.125f;
        int[] pix=new int[width*height];
        java.util.Arrays.fill(pix,0xff385594);
        // Original small thumbnail: top two red squares, 2 red squares
        // directly below its right column, exactly as in user's before.png.
        for(int index=0;index<4;index++) {
            int x=index==0?325:361;
            int y=index<2?1139:index==2?1175:1211;
            for(int yy=y;yy<y+34;yy++)for(int xx=x;xx<x+34;xx++)
                pix[yy*width+xx]=0xffc53131;
        }
        Game.Image before=new Game.Image(width,height,pix);
        Game.Frame frame=new Game.Frame(0x8080808080007f3fL,
            java.util.Collections.emptyList(),40,364,cell,width,height,1);
        Game.Piece redL=new Game.Piece(0,0,1,0,1,1,1,2);
        Game.Shape held=new Game.Shape(1,redL,377.5f,1192.5f,0xffc53131);
        Game.Image previous=lifted(before,frame,held,340,1034);
        Game.Image latest=lifted(before,frame,held,262,1034);
        // Previous sprite is fully novel; current sprite overlaps 2 small
        // thumbnail centers, despite all four large cells being red.
        check(DragAlignment.locateMoving(latest,before,previous,frame,held,
            364.00626f,1285.4375f,-52.08125f,0)==null,
            "strict checker correctly rejects two original red tray centers");
        DragAlignment.Origin recovered=DragAlignment.locatePickedAfterRetry(
            latest,before,previous,frame,held,364.00626f,1285.4375f,-52.08125f,0);
        check(recovered!=null&&Math.abs(recovered.x-262)<cell*.16f &&
            Math.abs(recovered.y-1034)<cell*.16f,
            "retry recovers left-moving L despite overlap with tray thumbnail");
        check(DragAlignment.locatePickedAfterRetry(previous,before,latest,frame,
            held,364.00626f,1285.4375f,-52.08125f,0)==null,
            "retry must still reject motion opposite the commanded direction");
        check(DragAlignment.locatePickedAfterRetry(latest,before,latest,frame,
            held,364.00626f,1285.4375f,-52.08125f,0)==null,
            "unchanged piece cannot masquerade as probe motion");
        check(DragAlignment.locatePickedAfterRetry(before,before,previous,frame,
            held,364.00626f,1285.4375f,-52.08125f,0)==null,
            "vanishing held piece cannot be accepted");
        check(DragAlignment.locatePickedAfterRetry(latest,before,previous,frame,
            held,364.00626f,1285.4375f,0f,0f)==null,
            "zero commanded probe cannot authorize recovery");
        Game.Image dup=lifted(latest,frame,held,490,1034);
        check(DragAlignment.locatePickedAfterRetry(dup,before,previous,frame,
            held,364.00626f,1285.4375f,-52.08125f,0)==null,
            "extra same-shape red sprite must cause rejection");
    }

    /** Confidence-gated online learning must never extrapolate unverified
     * or inconsistent touch offsets.  Coordinates are *pixel* origins and
     * finger positions as in the real 720x1560 game fixture.
     */
    static void verifiedDragCalibrationRegression() {
        DragCalibration model=new DragCalibration();
        Game.Piece box=new Game.Piece(0,0,1,0,0,1,1,1);
        float cell=80f;
        check(model.predict(0,box,cell,360,525,720,1560)==null,
            "untrained drag cannot use unverified direct placement");
        // Shape-normalized model bias: fingerX = spriteX + width*.5 cell
        // + .16 cell; fingerY = spriteY + height*.5 cell + 3.1 cell.
        DragCalibration.Observation s1=new DragCalibration.Observation(
            0,box.key,2,2,cell,350+1.16f*cell,500+4.1f*cell,350,500);
        DragCalibration.Observation s2=new DragCalibration.Observation(
            0,box.key,2,2,cell,220+1.20f*cell,340+4.05f*cell,220,340);
        check(model.recordVerified(s1),"first independently verified placement accepted");
        check(model.confidence(0)==1,"one success is insufficient for fast path");
        check(model.predict(0,box,cell,360,525,720,1560)==null,
            "fast mode requires two agreeing successful releases");
        check(model.recordVerified(s2),"agreeing second verified success accepted");
        float[] predicted=model.predict(0,box,cell,360,525,720,1560);
        check(predicted!=null,"two agreeing verified releases enable predicted drag");
        check(Math.abs(predicted[0]-(360+1.18f*cell))<2f &&
              Math.abs(predicted[1]-(525+4.075f*cell))<2f,
            "finger target predicted from learned screen-normalized lift");
        check(model.predict(1,box,cell,360,525,720,1560)==null,
            "slot 1 may not copy slot 0 calibration");
        Game.Piece one=new Game.Piece(0,0);
        float[] onePredicted=model.predict(0,one,cell,360,525,720,1560);
        check(onePredicted==null,
            "same slot but different shape cannot inherit another block's predicted drag offset");
        check(model.predict(0,box,cell,360,1320,720,1560)==null,
            "offscreen learned finger position must fall back to staged drag");
        check(model.predict(0,box,cell*2,360,525,1440,3120)==null,
            "cell geometry changes must invalidate stale calibration");
        check(!model.recordVerified(new DragCalibration.Observation(
            0,box.key,2,2,cell,Float.NaN,800,350,500)),
            "NaN motion sample cannot poison calibration");
        check(model.confidence(0)==2,"malformed sample does not erase valid model");
        check(model.recordVerified(new DragCalibration.Observation(
            0,box.key,2,2,cell,350+3*cell,500+4.1f*cell,350,500)),
            "outlying but finite sample causes controlled relearning");
        check(model.confidence(0)==1 &&
              model.predict(0,box,cell,360,525,720,1560)==null,
            "disagreeing slot offset disables direct drag until reconfirmed");
        model.reset();
        check(model.confirmedSamples()==0&&model.confidence(0)==0,
            "session reset forgets device-specific calibration");
    }

    /**
     * Uploaded v1.4.3 diagnostic (9): direct FAST drag of slot 1's BLUE
     * vertical 1x4 near the last board column. 19 total verified placements,
     * but only ONE prior direct success. The enlarged blue bar appears at
     * x~645,y~828 instead of the planned x600.125,y687.5, and extends
     * beyond the right capture edge (720px), causing ALIGN NO_HELD_MATCH.
     *
     * A per-slot estimate learned from other shapes is not evidence for this
     * 1x4 shape, and an edge target is dangerous even when this same exact
     * piece has two agreeing successes. Never modify full-sprite matching to
     * tolerate a clipped offscreen shape.
     */
    static void diagnostic9ExactShapeAndEdgeRegression() {
        float cell=79.875f;
        DragCalibration calibration=new DragCalibration();
        Game.Piece oldSlotShape=new Game.Piece(0,0,1,0,0,1,1,1);
        Game.Piece blueFour=new Game.Piece(0,0,0,1,0,2,0,3);
        Game.Piece anotherFour=new Game.Piece(0,0,1,0,2,0,3,0);
        for(int i=0;i<3;i++) {
            float x=300+i*3;
            check(calibration.recordVerified(new DragCalibration.Observation(
                1,oldSlotShape.key,2,2,cell,x+cell*1.13f,
                500+cell*4.13f,x,500)),
                "valid old-slot placement can train its own piece shape");
        }
        check(calibration.confidence(1)>=2,
            "historical mixed-shape slot can be calibrated");
        check(calibration.confidence(1,blueFour.key)==0,
            "blue 1x4 exact shape remains uncalibrated");
        check(calibration.predict(1,blueFour,cell,600.125f,687.5f,720,1560)==null,
            "slot-wide 2x2 calibration cannot direct blue 1x4 to right edge");
        for(int i=0;i<2;i++) {
            float x=280+i*4,y=550+i*4;
            check(calibration.recordVerified(new DragCalibration.Observation(
                1,blueFour.key,1,4,cell,x+cell*.7f,y+cell*5.8f,x,y)),
                "actual blue 1x4 successful placement trains only exact shape");
        }
        check(calibration.confidence(1,blueFour.key)==2,
            "two agreeing verified blue 1x4 placements are recognized");
        float[] center=calibration.predict(1,blueFour,cell,440,687.5f,720,1560);
        check(center!=null && Math.abs(center[0]-(440+cell*.7f))<1f,
            "exact shape may still use direct placement for an interior target");
        check(calibration.predict(1,blueFour,cell,600.125f,687.5f,720,1560)==null,
            "same-shape training must not risk clipping at x600.125 right edge");
        check(calibration.predict(1,blueFour,cell,41,687.5f,720,1560)==null,
            "leftmost board column is equally ineligible for predictive mode");
        check(calibration.predict(1,anotherFour,cell,440,687.5f,720,1560)==null,
            "same slot and same number of blocks but different geometry cannot share model");
        check(645+cell>720,
            "uploaded x645 whole sprite could not fit inside 720px screenshot");
        check(calibration.confirmedSamples()==5,
            "unrelated verified placements remain correctly counted");
    }

    static int simulateCorrection(float cells,float responseGain,boolean adaptive) {
        float cell=80f,error=cells*cell;
        int steps=0;
        for(;steps<16;steps++) {
            if(Math.abs(error)<cell*.13f)return steps;
            float command=adaptive?DragCalibration.correction(error,cell):
                DragStep.axis(error,cell);
            error-=command*responseGain;
        }
        return steps;
    }

    /** Deterministic offline SERVO simulation: not a measured game success
     * rate. Compare ONLY gesture counts across touch/render response gains.
     */
    static void adaptiveServoComparison() {
        double oldTotal=0,newTotal=0;
        int count=0;
        for(float gain:new float[]{.75f,1f,1.25f,1.5f})
            for(int index=-30;index<=30;index++) {
                float cells=index*.25f;
                int old=simulateCorrection(cells,gain,false);
                int improved=simulateCorrection(cells,gain,true);
                check(old<16&&improved<16,
                    "bounded adaptive servo converges under modeled gain "+gain);
                oldTotal+=old;newTotal+=improved;count++;
            }
        double saved=(oldTotal-newTotal)/oldTotal;
        check(saved>.17,"new modeled servo should save >17% correction strokes");
        check(Math.abs(DragCalibration.correction(1000,80))<=240.01f &&
              Math.abs(DragCalibration.correction(-1000,80))<=240.01f,
            "single open-loop correction never exceeds three cell widths");
        check(Math.abs(DragCalibration.correction(12,80)-8.64f)<.02f,
            "near target avoids blindly running full speed");
        boolean thrown=false;
        try{DragCalibration.correction(Float.NaN,80);}
        catch(IllegalArgumentException expected){thrown=true;}
        check(thrown,"invalid visual servo parameters cannot emit a bad touch");
        System.out.printf(java.util.Locale.US,
            "MODELED servo: %.1f%% fewer corrective strokes (%d scenarios, old=%.0f, new=%.0f)%n",
            saved*100,count,oldTotal,newTotal);
    }

    /**
     * Exact geometry from PRIVATE v1.4.0 diagnostics (7), NOT screenshots:
     * 720x1560, green vertical 1x5, thumbnail at x=342..377/y=1103..1282.
     * Full lifted image goes x=405 -> x=327 (-78px) after -51.9187px
     * controller probe. Three final cell centers overlap the SOURCE tray
     * thumbnail and are not new vs before; the other two genuinely move.
     * Long-piece tracking must accept ONLY that known-source exemption.
     */
    static void verticalFiveSelectedTrayOverlapRegression() {
        int w=720,h=1560;
        float cell=79.875f;
        int green=0xff31b642;
        int[] bg=new int[w*h];
        java.util.Arrays.fill(bg,0xff3a5194);
        for(int i=0;i<5;i++) {
            int minY=1103+i*36;
            for(int y=minY;y<minY+35;y++)
                for(int x=342;x<378;x++)bg[y*w+x]=green;
        }
        Game.Image before=new Game.Image(w,h,bg);
        Game.Frame frame=new Game.Frame(0x7f7f7f7f7f0082c0L,
            java.util.Collections.emptyList(),41,368,cell,w,h,1);
        Game.Piece vertical=new Game.Piece(0,0,0,1,0,2,0,3,0,4);
        Game.Shape selected=new Game.Shape(1,vertical,359.5f,1193,green);
        Game.Image previous=lifted(before,frame,selected,405,1074);
        Game.Image current=lifted(before,frame,selected,327,1074);
        // Before-image overlay leaves 3 stationary green centers, but new
        // sprite is 100% complete and motion direction is correct.
        int fresh=0;
        for(int i=0;i<5;i++) {
            int px=(int)(327+cell*.5f),py=(int)(1074+(i+.5f)*cell);
            if(Recognizer.distance(current.at(px,py),before.at(px,py))>60)fresh++;
        }
        check(fresh==2,"uploaded 1x5 failure has exactly two changed centers");
        check(DragAlignment.locateMoving(current,before,previous,frame,selected,
            363.99377f,1366.4375f,-51.918747f,0)==null,
            "strict normal match refuses source thumbnail overlaps");
        DragAlignment.Origin recovered=DragAlignment.locatePickedAfterRetry(
            current,before,previous,frame,selected,
            363.99377f,1366.4375f,-51.918747f,0);
        check(recovered!=null && Math.abs(recovered.x-327)<cell*.15f &&
            Math.abs(recovered.y-1074)<cell*.15f,
            "retry-only match follows actually left-moving complete green bar");
        check(DragAlignment.locatePickedAfterRetry(previous,before,current,
            frame,selected,363.99377f,1366.4375f,-51.918747f,0)==null,
            "reverse motion must be rejected even with tray overlap");
        check(DragAlignment.locatePickedAfterRetry(current,before,current,
            frame,selected,363.99377f,1366.4375f,-51.918747f,0)==null,
            "static green bar must not qualify as motion");
        check(DragAlignment.locatePickedAfterRetry(before,before,previous,
            frame,selected,363.99377f,1366.4375f,-51.918747f,0)==null,
            "source thumbnail alone cannot qualify as a picked long bar");

        // A pre-existing same-colored board/tray object OUTSIDE the source
        // thumbnail must never gain the exemption, even if a large green
        // shape appears later at that same location.
        int[] wrong=bg.clone();
        int cX=(int)(327+cell*.5f),cY=(int)(1074+3.5f*cell);
        // A whole already-colored center region must remain invalid even
        // if coarse-to-fine sprite search shifts its sampling origin.
        for(int y=cY-22;y<cY+22;y++)for(int x=cX-22;x<cX+22;x++)
            wrong[y*w+x]=green;
        Game.Image badBefore=new Game.Image(w,h,wrong);
        Game.Image badPrev=lifted(badBefore,frame,selected,405,1074);
        Game.Image badNow=lifted(badBefore,frame,selected,327,1074);
        check(DragAlignment.locatePickedAfterRetry(badNow,badBefore,badPrev,
            frame,selected,363.99377f,1366.4375f,-51.918747f,0)==null,
            "unrelated unchanged green center outside source cannot authorize pickup");
        Game.Image duplicate=lifted(current,frame,selected,520,1074);
        check(DragAlignment.locatePickedAfterRetry(duplicate,before,previous,
            frame,selected,363.99377f,1366.4375f,-51.918747f,0)==null,
            "two possible five-cell green sprites are ambiguous");
    }

    /**
     * v1.4.1 diagnostics (8) from user:
     * Selected green 1x5 block, captured 720x1560 / cell 79.875.
     * Visible at x~322,y~1075 after initial pickup and finally x~667,
     * y~715: sprite delta +345/-360 on finger delta +229.4/-239.625.
     * Gain is ~1.50 in BOTH axes. Old 1:1 sprite-error correction pushed
     * sprite beyond right edge, leaving a clipped last screenshot and
     * causing ALIGN/NO_HELD_MATCH. Probe was deliberately horizontal.
     */




    /**
     * v1.4.11 diagnostic (17): 720x1560, 79.5px cells. Held purple
     * staggered 3x2 tetromino (top: x1,x2; bottom: x0,x1) visibly at
     * origin ~(269,680), finger ~(447.5,1004). Three moving centers are
     * newly purple, but top-right overlaps an OLD purple BOARD square
     * at row=4,col=5. It is NOT a second moving block.
     */

    static void predictiveHeldRoiRegression() {
        // v1.4.13: ROI scans only a STRICTLY full, newly rendered sprite.
        int w=720,h=1560,background=0xff29334b,purple=0xff9054db;
        float cell=79.5f,left=43,top=368;
        Game.Piece p=new Game.Piece(0,0,1,0,0,1,1,1);
        Game.Shape shape=new Game.Shape(1,p,340,1185,purple);
        Game.Frame frame=new Game.Frame(0,java.util.Arrays.asList(shape),
            left,top,cell,w,h,1f);
        int[] base=new int[w*h];java.util.Arrays.fill(base,background);
        Game.Image before=new Game.Image(w,h,base);
        float actualX=345f,actualY=650f;
        Game.Image image=lifted(before,frame,shape,actualX,actualY);
        float expectedX=actualX+cell*.26f,expectedY=actualY-cell*.17f;
        DragAlignment.Origin tight=DragAlignment.locatePredicted(
            image,before,frame,shape,expectedX,expectedY,.9f);
        check(tight!=null && Math.abs(tight.x-actualX)<cell*.3f &&
            Math.abs(tight.y-actualY)<cell*.3f,
            "correct moving sprite found around predicted coordinates");
        check(DragAlignment.locatePredicted(image,before,frame,shape,
            actualX+cell*1.7f,actualY, .9f)==null,
            "wrong motion estimate does not become a sighting");
        DragAlignment.Origin widened=DragAlignment.locatePredicted(
            image,before,frame,shape,actualX+cell*1.7f,actualY,2.3f);
        check(widened!=null && Math.abs(widened.x-actualX)<cell*.3f,
            "wider ROI recovers plausible commanded-motion offset");
        check(DragAlignment.locatePredicted(image,before,frame,shape,
            30f,150f,2.3f)==null,
            "missed ROI cannot invent a held piece");
        check(DragAlignment.locate(image,before,frame,shape,
            460f,880f)!=null,
            "wider established search remains available on ROI miss");
        check(DragAlignment.locatePredicted(before,before,frame,shape,
            expectedX,expectedY,.9f)==null,
            "same stale pixels cannot be reported as fresh movement");
        int[] partial=image.pixels.clone();
        for(int y=650;y<650+(int)cell;y++)for(int x=345;x<345+(int)cell;x++)
            partial[y*w+x]=background;
        check(DragAlignment.locatePredicted(
            new Game.Image(w,h,partial),before,frame,shape,
            expectedX,expectedY,2.3f)==null,
            "three quarters of piece is not strong enough to pass");
        check(DragAlignment.locatePredicted(image,before,frame,shape,
            Float.NaN,expectedY,.9f)==null,
            "nonfinite predicted state is never used");
    }

    static void diagnostic17PurpleShiftedBoardOverlap() {
        int w=720,h=1560,bg=0xff212d52,purple=0xff9259d6;
        float cell=79.5f,left=43,top=368;
        Game.Piece shifted=new Game.Piece(1,0,2,0,0,1,1,1);
        Game.Shape selected=new Game.Shape(2,shifted,569.5f,1174f,purple);
        long oneExisting=1L<<(4*8+5);
        Game.Frame frame=new Game.Frame(oneExisting,java.util.Arrays.asList(selected),
            left,top,cell,w,h,1f);
        int[] pixels=new int[w*h];java.util.Arrays.fill(pixels,bg);
        paintCell(pixels,w,h,left,top,cell,5,4,purple);
        // A second same-colored STATIC tile directly next to the held
        // piece must not be accepted as one of its occupied cells.
        paintCell(pixels,w,h,left,top,cell,5,5,purple);
        Game.Image before=new Game.Image(w,h,pixels);
        Game.Image held=lifted(before,frame,selected,269,685);
        check(DragAlignment.locate(held,before,frame,selected,
            447.49954f,1004f)==null,
            "old all-centers-new tracker must reject diag17 purple overlap");
        DragAlignment.Origin match=DragAlignment.locateOverOccupiedBoard(
            held,before,frame,selected,447.49954f,1004f);
        check(match!=null && Math.abs(match.x-269)<cell*.23f &&
            Math.abs(match.y-685)<cell*.23f,
            "4-cell shifted purple piece recovered despite one old board center");
        Game.Frame noOld=new Game.Frame(0,frame.shapes,left,top,cell,w,h,1);
        check(DragAlignment.locateOverOccupiedBoard(
            held,before,noOld,selected,447.49954f,1004f)==null,
            "one old-color center without board occupancy must fail");
        Game.Image staticFrame=new Game.Image(w,h,pixels.clone());
        check(DragAlignment.locateOverOccupiedBoard(
            staticFrame,before,frame,selected,447.49954f,1004f)==null,
            "unmoved same-color board tiles cannot masquerade as picked sprite");
        int[] notFour=held.pixels.clone();
        paintCell(notFour,w,h,left,top,cell,3,5,bg);
        check(DragAlignment.locateOverOccupiedBoard(
            new Game.Image(w,h,notFour),before,frame,selected,
            447.49954f,1004f)==null,
            "missing any of the three novel purple centers must fail");
        // A second unchanged center disqualifies the single-tile exemption.
        int[] twoOld=pixels.clone();
        paintCell(twoOld,w,h,left,top,cell,4,4,purple);
        Game.Image beforeTwo=new Game.Image(w,h,twoOld);
        Game.Image heldTwo=lifted(beforeTwo,frame,selected,269,685);
        Game.Frame bothOld=new Game.Frame(oneExisting|(1L<<(4*8+4)),
            frame.shapes,left,top,cell,w,h,1);
        check(DragAlignment.locateOverOccupiedBoard(
            heldTwo,beforeTwo,bothOld,selected,447.49954f,1004f)==null,
            "two stationary purple centers are not an eligible recovery");
        // Existing 2x3/green regression also remains in full test suite.
    }

    static void diagnostic16OrangeDarkBevelRegression() {
        // Actual diag16 parameters: 720x1560, 79.625 cell, target
        // (42,766.125), finger (176.8625,1114.5804), moved orange
        // 2x3 zigzag visibly around x~145-160/y~810. The source tray's
        // orange face was (230,121,33), moving centers (156,69,8).
        int w=720,h=1560,bg=0xff212d52;
        float cell=79.625f,left=42f,top=368f;
        long board=0xfc00ee8900000001L;
        Game.Piece zigzag=new Game.Piece(0,0,0,1,1,1,1,2);
        Game.Shape picked=new Game.Shape(0,zigzag,131.5f,1192f,0xffe67921);
        Game.Frame frame=new Game.Frame(board,java.util.Arrays.asList(picked),
            left,top,cell,w,h,1f);
        int[] base=new int[w*h];java.util.Arrays.fill(base,bg);
        for(int r=0;r<8;r++)for(int c=0;c<8;c++)
            if((board&(1L<<(8*r+c)))!=0)
                paintCell(base,w,h,left,top,cell,c,r,0xffea933d);
        Game.Image before=new Game.Image(w,h,base);
        int[] pix=base.clone();
        float ox=153f,oy=812f;
        for(int i=0;i<4;i++) {
            float x0=ox+zigzag.xs[i]*cell,y0=oy+zigzag.ys[i]*cell;
            for(int y=(int)y0;y<y0+cell;y++)
                for(int x=(int)x0;x<x0+cell;x++) {
                    if(x<0||y<0||x>=w||y>=h)continue;
                    pix[y*w+x]=y<y0+cell*.42f?0xffe67921:0xff9c4508;
                }
        }
        Game.Image moving=new Game.Image(w,h,pix);
        DragAlignment.Origin recovered=DragAlignment.locateBeveledOrangeZigzag(
            moving,before,frame,picked,176.8625f,1114.5804f);
        check(recovered!=null && Math.abs(recovered.x-ox)<cell*.40f &&
              Math.abs(recovered.y-oy)<cell*.22f,
            "dark beveled actual zigzag recovered without strict-color centers");
        check(DragAlignment.locateBeveledOrangeZigzag(
            before,before,frame,picked,176.8625f,1114.5804f)==null,
            "unchanged board and source thumbnail are not moving pieces");
        int[] missing=pix.clone();
        for(int y=(int)(oy+2*cell);y<oy+3*cell;y++)
            for(int x=(int)(ox+cell);x<ox+2*cell;x++)
                if(x>=0&&x<w&&y>=0&&y<h)missing[y*w+x]=bg;
        check(DragAlignment.locateBeveledOrangeZigzag(
            new Game.Image(w,h,missing),before,frame,
            picked,176.8625f,1114.5804f)==null,
            "missing fourth cell may not be accepted as held zigzag");
        check(DragAlignment.locateBeveledOrangeZigzag(
            moving,before,frame,picked,610f,1114.5804f)==null,
            "unrelated distant finger position may not recover sprite");
        Game.Piece other=new Game.Piece(0,0,1,0,0,1,1,1);
        check(DragAlignment.locateBeveledOrangeZigzag(
            moving,before,frame,new Game.Shape(0,other,131f,1192f,picked.color),
            176.8625f,1114.5804f)==null,
            "wrong shape cannot borrow dark-orange zigzag tracking");
        int[] uniform=base.clone();
        for(int c=1;c<6;c++)
            paintCell(uniform,w,h,left,top,cell,c,7,0xffe67921);
        check(DragAlignment.locateBeveledOrangeZigzag(
            new Game.Image(w,h,uniform),before,frame,picked,
            176.8625f,1114.5804f)==null,
            "static bottom-row orange is not a separately moving zigzag");
    }

    static void diagnostic15BlueLineGlowRegression() {
        int w=720,h=1560,bg=0xff212d52;
        float cell=79.875f,left=41f,top=368f;
        long board=0xfcf7000900000001L;
        Game.Piece blueL=new Game.Piece(0,0,1,0,2,0,2,1);
        Game.Shape selected=new Game.Shape(1,blueL,358f,1174f,0xff3a5dde);
        Game.Frame frame=new Game.Frame(board,java.util.Arrays.asList(selected),
            left,top,cell,w,h,1f);
        int[] original=new int[w*h];java.util.Arrays.fill(original,bg);
        for(int r=0;r<8;r++)for(int col=0;col<8;col++)
            if((board&(1L<<(r*8+col)))!=0)
                paintCell(original,w,h,left,top,cell,col,r,0xffbd3b34);
        Game.Image before=new Game.Image(w,h,original);
        check(Rules.place(board,blueL,1,5).lines==1,
            "diag15 original board+blue L clears exactly bottom shape row");
        float tx=left+cell,ty=top+5*cell;
        int[] glowing=original.clone();
        for(int col=0;col<8;col++)
            paintCell(glowing,w,h,left,top,cell,col,6,0xff4265e6);
        Game.Image glowOnly=new Game.Image(w,h,glowing);
        check(DragAlignment.locateLineGlowRightL(glowOnly,before,frame,selected,
            tx,ty,289.107f,1076.834f)==null,
            "glowing blue LINE ALONE is never a held right L");
        int[] held=glowing.clone();
        for(int i=0;i<blueL.xs.length;i++){
            int col=1+blueL.xs[i],row=5+blueL.ys[i];
            float cellX=left+col*cell,cellY=top+row*cell;
            for(int y=(int)cellY;y<cellY+cell;y++)for(int x=(int)cellX;x<cellX+cell;x++){
                if(x<0||y<0||x>=w||y>=h)continue;
                // Bright beveled top, deep-blue lower face.
                int shade=y<cellY+cell*.5f?
                    ((row==6)?0xffaddbff:0xff94b6ff):0xff4265e6;
                held[y*w+x]=shade;
            }
        }
        Game.Image current=new Game.Image(w,h,held);
        check(DragAlignment.locateLineGlowRightL(current,before,frame,selected,
            tx,ty,289.107f,1076.834f)!=null,
            "four colored face interiors and lower-right bevel over glowing row");
        check(DragAlignment.locateLineGlowRightL(current,before,frame,selected,
            tx,ty,690f,1076.834f)==null,
            "unrelated finger never authorizes glow preview");
        check(DragAlignment.locateLineGlowRightL(current,before,frame,selected,
            tx,ty+cell,289.107f,1076.834f)==null,
            "wrong target row never authorizes glow preview");
        int[] uniform=glowing.clone();
        for(int i=0;i<3;i++)
            paintCell(uniform,w,h,left,top,cell,1+i,5,0xff4265e6);
        check(DragAlignment.locateLineGlowRightL(
            new Game.Image(w,h,uniform),before,frame,selected,
            tx,ty,289.107f,1076.834f)==null,
            "uniformly colored glow without true bevel cannot mimic held L");
        int[] missing=held.clone();
        paintCell(missing,w,h,left,top,cell,2,5,bg);
        check(DragAlignment.locateLineGlowRightL(
            new Game.Image(w,h,missing),before,frame,selected,
            tx,ty,289.107f,1076.834f)==null,
            "missing L cell must block release");
        Game.Frame noClear=new Game.Frame(0L,frame.shapes,
            left,top,cell,w,h,1f);
        check(DragAlignment.locateLineGlowRightL(current,before,noClear,selected,
            tx,ty,289.107f,1076.834f)==null,
            "legitimate non-combo placement cannot use this one-row recovery");
    }

    static void diagnostic14ComboGlowRegression() {
        int width=720,height=1560,bg=0xff293052;
        float cell=79.625f,left=42f,top=368f;
        long board=0xfbf8020202020203L;
        Game.Piece redL=new Game.Piece(0,0,1,0,2,0,2,1);
        Game.Shape selected=new Game.Shape(2,redL,569.5f,1174.5f,0xffe33c47);
        Game.Frame frame=new Game.Frame(board,java.util.Arrays.asList(selected),
            left,top,cell,width,height,1f);
        int[] initial=new int[width*height];
        java.util.Arrays.fill(initial,bg);
        for(int row=0;row<8;row++)for(int col=0;col<8;col++)
            if((board&(1L<<(row*8+col)))!=0)
                paintCell(initial,width,height,left,top,cell,col,row,0xffdb692b);
        Game.Image before=new Game.Image(width,height,initial);
        int[] glow=initial.clone();
        // Reconstruct the THREE-line preview from diag14:
        // both bottom rows and vertical column 1 glow dull red.
        for(int row=6;row<=7;row++)for(int col=0;col<8;col++)
            paintCell(glow,width,height,left,top,cell,col,row,0xffc53135);
        for(int row=0;row<8;row++)
            paintCell(glow,width,height,left,top,cell,1,row,0xffc53135);
        Game.Image onlyGlow=new Game.Image(width,height,glow.clone());
        float tx=left,ty=top+6*cell;
        check(Rules.place(board,redL,0,6).lines==3,
            "diag14 source board + L forms two rows and one column");
        check(DragAlignment.locateComboClearPreview(
            onlyGlow,before,frame,selected,tx,ty,307.06f,1126.74f)==null,
            "clearing-line red glow ALONE can never authorize release");
        int[] preview=glow.clone();
        for(int i=0;i<redL.xs.length;i++)
            paintCell(preview,width,height,left,top,cell,
                redL.xs[i],6+redL.ys[i],0xffef787a);
        Game.Image actual=new Game.Image(width,height,preview);
        check(DragAlignment.targetCellPreview(actual,before,frame,selected,0,6),
            "all four actual L face cells visibly replace the original board");
        DragAlignment.Origin recovered=DragAlignment.locateComboClearPreview(
            actual,before,frame,selected,tx,ty,307.06f,1126.74f);
        check(recovered!=null && Math.abs(recovered.x-tx)<.001f &&
            Math.abs(recovered.y-ty)<.001f,
            "strong L sprite faces over weak glow recover exact three-line target");
        check(DragAlignment.locateComboClearPreview(
            actual,before,frame,selected,tx,ty,10f,1126.74f)==null,
            "implausible held finger cannot authorize glowing board");
        int[] incomplete=preview.clone();
        paintCell(incomplete,width,height,left,top,cell,2,7,0xff293052);
        check(DragAlignment.locateComboClearPreview(
            new Game.Image(width,height,incomplete),before,frame,selected,
            tx,ty,307.06f,1126.74f)==null,
            "one missing L-face cell still rejects release");
        Game.Frame noClear=new Game.Frame(0L,frame.shapes,
            left,top,cell,width,height,1f);
        check(DragAlignment.locateComboClearPreview(
            actual,before,noClear,selected,tx,ty,307.06f,1126.74f)==null,
            "ordinary non-combo placements cannot use this recovery");
    }

    static void paintCell(int[] pixels,int width,int height,
            float left,float top,float cell,int x,int y,int rgb) {
        int x0=Math.max(0,(int)(left+x*cell)), x1=Math.min(width,(int)(left+(x+1)*cell));
        int y0=Math.max(0,(int)(top+y*cell)), y1=Math.min(height,(int)(top+(y+1)*cell));
        for(int yy=y0;yy<y1;yy++)for(int xx=x0;xx<x1;xx++)
            pixels[yy*width+xx]=rgb;
    }

    static void diagnostic13OccupiedGreenBoardRegression() {
        // User v1.4.7 diagnostic (13): full 2x3 green sprite
        // at approximately (371,671), target (361,447.5), finger
        // (402.16,1043.75), field 720x1560, cell 79.5.
        // The one unchanged green center is actually occupied
        // board coordinate col=4,row=4.
        int w=720,h=1560,green=0xff21c63f,bg=0xff30466d;
        int[] old=new int[w*h];
        java.util.Arrays.fill(old,bg);
        for(int y=687;y<764;y++)for(int x=361;x<439;x++)
            old[y*w+x]=green;
        Game.Image before=new Game.Image(w,h,old);
        float cell=79.5f;
        Game.Piece twoByThree=new Game.Piece(
            0,0,1,0,0,1,1,1,0,2,1,2);
        Game.Shape selected=new Game.Shape(1,twoByThree,341.5f,1192.5f,green);
        long board=1L<<(4*8+4);
        Game.Frame frame=new Game.Frame(board,java.util.Arrays.asList(selected),
            43f,368f,cell,w,h,1f);
        Game.Image moved=lifted(before,frame,selected,371,671);
        check(DragAlignment.locate(moved,before,frame,selected,
                402.16f,1043.75f)==null,
            "normal all-new-centres lookup rejects overlapping old green tile");
        DragAlignment.Origin recovered=DragAlignment.locateOverOccupiedBoard(
            moved,before,frame,selected,402.16f,1043.75f);
        check(recovered!=null && Math.abs(recovered.x-371)<cell*.18f &&
                Math.abs(recovered.y-671)<cell*.18f,
            "exactly one occupied old same-color center is recoverable");

        Game.Frame noBoard=new Game.Frame(0L,frame.shapes,43,368,cell,w,h,1);
        check(DragAlignment.locateOverOccupiedBoard(
            moved,before,noBoard,selected,402.16f,1043.75f)==null,
            "without actual board occupancy no old-color exemption");
        Game.Image unchanged=new Game.Image(w,h,old.clone());
        check(DragAlignment.locateOverOccupiedBoard(
            unchanged,before,frame,selected,402.16f,1043.75f)==null,
            "static board square cannot masquerade as a held 2x3");
        // Duplicate becomes genuinely ambiguous only if BOTH candidates
        // have their own old occupied same-color board center. A duplicate
        // that is fully new (no board overlap) must not be mistaken for a
        // valid overlap-recovery candidate.
        int[] bothOld=old.clone();
        for(int y=687;y<764;y++)for(int x=123;x<201;x++)
            bothOld[y*w+x]=green;
        Game.Image beforeTwo=new Game.Image(w,h,bothOld);
        Game.Frame twoBoard=new Game.Frame(
            board|(1L<<(4*8+1)),frame.shapes,43,368,cell,w,h,1);
        Game.Image second=lifted(beforeTwo,twoBoard,selected,133,671);
        Game.Image twoCandidates=lifted(second,twoBoard,selected,371,671);
        check(DragAlignment.locateOverOccupiedBoard(
            twoCandidates,beforeTwo,twoBoard,selected,402.16f,1043.75f)==null,
            "two equally plausible occupied-board-overlap green shapes must be rejected");
        int[] missing=moved.pixels.clone();
        for(int y=830;y<907;y++)for(int x=450;x<527;x++)
            missing[y*w+x]=bg;
        check(DragAlignment.locateOverOccupiedBoard(
            new Game.Image(w,h,missing),before,frame,
            selected,402.16f,1043.75f)==null,
            "one missing 2x3 cell must invalidate whole-shape evidence");
    }

    static void diagnostic11StackedPreviewRegression() {
        // Field evidence: 720x1560 / cell 79.875, a cyan 1x4 preview
        // at board column 4 row 0 and a separate held 1x4 immediately
        // below that preview, at the actual finger ~386,836.
        int w=720,h=1560;
        int background=0xff212852,face=0xff21aede,shade=0xff006da4;
        int[] base=new int[w*h];java.util.Arrays.fill(base,background);
        Game.Image before=new Game.Image(w,h,base);
        int[] moving=base.clone();
        float cell=79.875f,left=41f,top=368f;
        Game.Piece vertical=new Game.Piece(0,0,0,1,0,2,0,3);
        Game.Shape selected=new Game.Shape(1,vertical,359.5f,1173.5f,0xff31b6e6);
        Game.Frame frame=new Game.Frame(0L,java.util.Arrays.asList(selected),
            left,top,cell,w,h,1f);
        // Approximate the actual beveled face: the top is bright but
        // the lower half is deeply shaded yet still has the cyan hue.
        for(int j=0;j<8;j++)for(int dy=0;dy<80;dy++) {
            int y=350+j*80+dy;
            if(y<0||y>=h)continue;
            for(int x=358;x<438;x++)moving[y*w+x]=dy<52?face:shade;
        }
        Game.Image stacked=new Game.Image(w,h,moving);
        float tx=left+4*cell,ty=top;
        check(DragAlignment.targetCellPreview(stacked,before,frame,selected,4,0),
            "actual 4-cell cyan target preview accepts genuine beveled dark face");
        DragAlignment.Origin verified=DragAlignment.locateHeldBelowSnappedPreview(
            stacked,before,frame,selected,tx,ty,386f,835f);
        check(verified!=null && Math.abs(verified.x-tx)<.01f &&
            Math.abs(verified.y-ty)<.01f,
            "stacked preview + held-finger material gives restricted grid proxy");
        check(DragAlignment.locateHeldBelowSnappedPreview(
            stacked,before,frame,selected,tx,ty,650f,835f)==null,
            "unrelated finger column cannot authorize a snapped preview");
        check(DragAlignment.locateHeldBelowSnappedPreview(
            stacked,before,frame,selected,tx,ty,386f,650f)==null,
            "preview without a separately held sprite below cannot authorize release");
        Game.Image stale=new Game.Image(w,h,moving);
        check(!DragAlignment.targetCellPreview(stale,stacked,frame,selected,4,0),
            "stale identical pixels cannot be new target evidence");
        int[] missing=moving.clone();
        for(int y=350+80*2;y<350+80*3;y++)for(int x=358;x<438;x++)
            missing[y*w+x]=background;
        Game.Image gap=new Game.Image(w,h,missing);
        check(!DragAlignment.targetCellPreview(gap,before,frame,selected,4,0),
            "one missing target square must reject the stacked preview");
        int[] wrong=moving.clone();
        for(int y=350;y<350+80*4;y++)for(int x=358;x<438;x++)
            wrong[y*w+x]=0xffdf3434;
        Game.Image wrongColor=new Game.Image(w,h,wrong);
        check(!DragAlignment.targetCellPreview(wrongColor,before,frame,selected,4,0),
            "wrong hue never counts as the selected cyan block");
        check(DragAlignment.locateHeldBelowSnappedPreview(
            wrongColor,before,frame,selected,tx,ty,386f,835f)==null,
            "nearby held material alone cannot replace complete target proof");
    }

    static void measuredSpriteResponseRegression() {
        float cell=79.875f;
        DragResponse response=new DragResponse();
        check(!response.measured(),"new held stroke starts without measured gain");
        check(Math.abs(response.gainX()-1.45f)<.001f &&
              Math.abs(response.gainY()-1.45f)<.001f,
            "unknown device uses cautious response prior, not aggressive 1:1");
        check(!response.observe(-52,0,78,0,cell),
            "opposite-signed visual move cannot poison controller gain");
        check(!response.measured(),"rejected observation leaves model untrained");
        int width=720,height=1560;
        int[] pix=new int[width*height];
        java.util.Arrays.fill(pix,0xff385594);
        Game.Image before=new Game.Image(width,height,pix);
        Game.Frame frame=new Game.Frame(0L,java.util.Collections.emptyList(),
            41,368,cell,width,height,1);
        Game.Piece vertical=new Game.Piece(0,0,0,1,0,2,0,3,0,4);
        Game.Shape shape=new Game.Shape(1,vertical,359.5f,1193f,0xff2ab83e);
        Game.Image first=lifted(before,frame,shape,322,1075);
        Game.Image second=lifted(before,frame,shape,405,1075);
        DragAlignment.Motion evidence=DragAlignment.locateMovingEvidence(
            second,before,first,frame,shape,415.9125f,1366.4375f,55.9125f,0);
        check(evidence!=null && Math.abs(evidence.movedX-83)<cell*.15f,
            "source-aligned probe must return the witnessed whole-sprite displacement");
        check(response.observe(55.9125f,0,evidence.movedX,evidence.movedY,cell),
            "uniquely tracked horizontal probe calibrates per-stroke gain");
        check(response.measured() && response.gainX()>1.3f &&
            response.gainX()<1.7f &&
            Math.abs(response.gainY()-response.gainX())<.01f,
            "visual response gain is about 1.5 and provisionally shared by Y");

        float wantedX=600.125f,wantedY=607.625f;
        float oldFingerDX=DragCalibration.correction(wantedX-405,cell);
        check(405+1.5f*oldFingerDX>width-cell,
            "old controller overshoots the 1x5 sprite beyond right capture edge");
        float[] safer=response.correction(wantedX-405,wantedY-1075,
            405,1075,1,5,cell,width,height);
        check(safer[0]>0 && safer[0]<oldFingerDX,
            "calibrated x-command is shorter than old 1:1 correction");
        check(safer[1]<0 && safer[1]>=-3*cell-.01f,
            "calibrated y-command stays within bounded three-cell travel");
        float nx=405+1.5f*safer[0],ny=1075+1.5f*safer[1];
        check(nx+cell<=width && ny>=0 && ny+5*cell<=height,
            "first observed step leaves ALL five cells inside capture");
        check(Math.abs(nx-wantedX)<cell*.25f,
            "first calibrated step almost reaches target x without clipping");
        float[] secondStep=response.correction(wantedX-nx,wantedY-ny,
            nx,ny,1,5,cell,width,height);
        float finalX=nx+1.5f*secondStep[0],
              finalY=ny+1.5f*secondStep[1];
        check(Math.abs(finalX-wantedX)<cell*.14f &&
              Math.abs(finalY-wantedY)<cell*.14f,
            "two feedback corrections converge within a safe target-cell basin");
        check(!response.observe(60,0,0,80,cell),
            "orthogonal sprite jump on a horizontal probe is unsafe");
        check(!response.observe(60,0,220,0,cell),
            "implausible 3.7x motion must not overwrite verified scale");
        check(response.observe(120,-160,180,-240,cell),
            "real verified diagonal motion updates both X and Y gain");
        check(Math.abs(response.gainX()-1.5f)<.20f &&
              Math.abs(response.gainY()-1.5f)<.20f,
            "diagonal evidence reinforces the measured 1.5x response");
        float[] boundary=response.correction(250,0,626,700,
            1,5,cell,width,height);
        check(626+1.5f*boundary[0]+cell<=width,
            "sprite width is always guarded against the right capture border");
        boolean badRejected=false;
        try{response.correction(300,200,667,715,
            1,5,cell,width,height);}
        catch(IllegalArgumentException expected){badRejected=true;}
        check(badRejected,"already clipped sprite must safely stop instead of blindly moving");

        // User screenshot regression: a 3x3 L may be tracked a few pixels
        // beyond the strict inset, even though most of the shape is visible.
        // Only a provably inward held-pointer correction is permitted.
        float c=80f,margin=Math.max(2f,c*.04f);
        float rightEdge=width-3*c-margin;
        float atRight=rightEdge+9f;
        float[] inward=response.correction(-70,0,atRight,1100,3,3,c,width,height);
        check(inward[0]<0 && atRight+response.gainX()*inward[0]<rightEdge,
            "slightly overhanging right-edge L must recover inward while held");
        float[] topRecovery=response.correction(0,50,200,-5,3,3,c,width,height);
        check(topRecovery[1]>0 &&
              -5+response.gainY()*topRecovery[1]>margin,
            "slightly overhanging top edge recovers inward without releasing");
        boolean outwardRejected=false;
        try{response.correction(+70,0,atRight,1100,3,3,c,width,height);}
        catch(IllegalArgumentException expected){outwardRejected=true;}
        check(outwardRejected,"edge recovery must NEVER continue outward");
        boolean deepClipRejected=false;
        try{response.correction(-70,0,rightEdge+25,1100,3,3,c,width,height);}
        catch(IllegalArgumentException expected){deepClipRejected=true;}
        check(deepClipRejected,"significantly clipped L remains a hard safety stop");

        response.reset();
        check(!response.measured() &&
            Math.abs(response.gainX()-1.45f)<.001f,
            "each newly held block starts a fresh motor response calibration");
    }

    /**
     * v1.4.3: latency reduction is NEVER enabled solely by an accessibility
     * onCompleted callback or by visual movement. Only an actual board/tray
     * placement verified through MoveVerifier may invoke verified().
     * Simulated gesture budgets are time parameters, NOT real-game speedups.
     */
    static void successGatedSpeedRegression() {
        AdaptivePace pace=new AdaptivePace();
        check(pace.tier()==AdaptivePace.Tier.BASE,"new autoplay begins at known-good v1.4.2 timing");
        check(pace.pickupHoldMs()==160,"game's established press-to-pick duration is preserved");
        check(pace.firstTravelMs()==500 && pace.probeMs()==320 &&
              pace.correctionMs()==350 && pace.releaseMs()==150,
              "cold-start keeps exact proven gesture durations");
        check(pace.postGestureReadMs()==160 && pace.framePollMs()==130 &&
              pace.boardStableMs()==200 && pace.heldStableMs()==180,
              "cold-start keeps exact proven capture timing");
        pace.verified(1,5);
        check(pace.tier()==AdaptivePace.Tier.BASE,
            "one confirmed placement does not activate untested fast path");
        pace.verified(2,5);
        check(pace.tier()==AdaptivePace.Tier.QUICK,
            "two clean verified placements activate moderate speed");
        check(pace.firstTravelMs()==405 && pace.probeMs()==265 &&
              pace.correctionMs()==295 && pace.releaseMs()==140,
              "moderate speed shortens travel without skipping phases");
        check(pace.heldStableMs()>=150 && pace.boardStableMs()>=175,
            "visual identity and actual board verification require independent stability");
        for(int i=0;i<3;i++)pace.verified(1,4);
        check(pace.tier()==AdaptivePace.Tier.FAST,
            "five consecutively clean placements activate fast path");
        check(pace.firstTravelMs()==345 && pace.probeMs()==225 &&
              pace.correctionMs()==260 && pace.releaseMs()==130,
              "fast path remains bounded by conservative gesture durations");
        check(pace.postGestureReadMs()>=110 && pace.framePollMs()>=85,
            "faster polling still leaves the game time to render");
        check(pace.pickupHoldMs()==160,
            "faster mode never shortens press-to-pick safety dwell");
        int oldBudget=pace.pickupHoldMs()+500+320+350+150+2*160+2*130+200+180;
        int fastBudget=pace.pickupHoldMs()+pace.firstTravelMs()+pace.probeMs()+
            pace.correctionMs()+pace.releaseMs()+2*pace.postGestureReadMs()+
            2*pace.framePollMs()+pace.boardStableMs()+pace.heldStableMs();
        double savings=(oldBudget-fastBudget)*100.0/oldBudget;
        check(savings>20 && savings<40,"modeled schedule saves time without reckless sub-100ms strokes");
        System.out.printf(java.util.Locale.US,
            "MODELED pace: %.1f%% shorter scheduled gesture/wait budget (%dms to %dms, not real game latency)%n",
            savings,oldBudget,fastBudget);

        pace.uncertainty();
        check(pace.tier()==AdaptivePace.Tier.BASE && pace.cleanPlacements()==0,
            "an uncertain held-piece match resets speed before next command");
        pace.verified(0,1);
        check(pace.cleanPlacements()==0,"single gesture callback without full placement ignored");
        pace.verified(3,7);
        check(pace.cleanPlacements()==0,"too many corrections prevent unsafe acceleration");
        pace.verified(1,5);pace.verified(1,5);
        check(pace.tier()==AdaptivePace.Tier.QUICK,"can safely earn moderate speed again");
        pace.verified(4,8);
        check(pace.tier()==AdaptivePace.Tier.BASE,
            "unexpected correction count forces immediate conservative speed");
        pace.reset();
        check(pace.tier()==AdaptivePace.Tier.BASE,"new session cannot inherit old fast mode");
    }

    /** Diagnostic (19): the real red L has TWO unchanged same-color board
     * centers at (column3,row4) and (column3,row6), plus 3 new centers. */
    static void diagnostic19RedLTwoOldBoardRegression() {
        final int w=720,h=1560,bg=0xff212852,red=0xffc53131;
        final float left=44f,top=366f,cell=79.125f;
        Game.Piece l=new Game.Piece(0,0,0,1,0,2,1,2,2,2);
        Game.Shape shape=new Game.Shape(0,l,113.5f,1192.5f,red);
        long occupied=(1L<<(4*8+3))|(1L<<(6*8+3));
        Game.Frame frame=new Game.Frame(occupied,java.util.Arrays.asList(shape),
            left,top,cell,w,h,1f);
        int[] background=new int[w*h];java.util.Arrays.fill(background,bg);
        paintCell(background,w,h,left,top,cell,3,4,red);
        paintCell(background,w,h,left,top,cell,3,6,red);
        paintCell(background,w,h,left,top,cell,4,6,0xff31b63a);
        paintCell(background,w,h,left,top,cell,5,6,0xff4265de);
        Game.Image before=new Game.Image(w,h,background);
        Game.Image held=lifted(before,frame,shape,289f,663f);
        float fx=286.66293f,fy=1038.5625f;
        check(DragAlignment.locate(held,before,frame,shape,fx,fy)==null,
            "diag19 ordinary new-center matching must still reject overlaps");
        DragAlignment.Origin match=DragAlignment.locateOverOccupiedBoard(
            held,before,frame,shape,fx,fy);
        check(match!=null && Math.abs(match.x-289f)<cell*.23f &&
            Math.abs(match.y-663f)<cell*.23f,
            "diag19 real red L with 2 old board centers must be recoverable");
        Game.Frame missingBit=new Game.Frame(1L<<(4*8+3),
            frame.shapes,left,top,cell,w,h,1);
        check(DragAlignment.locateOverOccupiedBoard(
            held,before,missingBit,shape,fx,fy)==null,
            "diag19 both original board bits must be verified, independently");
        check(DragAlignment.locateOverOccupiedBoard(
            before,before,frame,shape,fx,fy)==null,
            "diag19 a stationary board must not pass the recovery");
        int[] erased=held.pixels.clone();
        paintCell(erased,w,h,left,top,cell,5,6,bg);
        check(DragAlignment.locateOverOccupiedBoard(
            new Game.Image(w,h,erased),before,frame,shape,fx,fy)==null,
            "diag19 incomplete right tail is unsafe");
    }

    /** Diagnostic 20: three of four yellow sprite cells sit directly on
     * old yellow board tiles; center RGB drift is only 4 levels. */
    static void diagnostic20TemporalOcclusionRegression() {
        int w=720,h=1560,bg=0xff212852;
        float left=41f,top=368f,cell=79.875f;
        int oldYellow=0xffe6b23a,heldYellow=0xffe6ae3a,selected=0xffefbe3a;
        Game.Piece piece=new Game.Piece(1,0,0,1,1,1,0,2);
        Game.Shape shape=new Game.Shape(0,piece,131.5f,1191.5f,selected);
        long board=1L<<(4*8+7) | 1L<<(5*8+6) |
                   1L<<(5*8+7) | 1L<<(6*8+6);
        Game.Frame frame=new Game.Frame(board,java.util.Arrays.asList(shape),
            left,top,cell,w,h,1f);
        int[] raw=new int[w*h];java.util.Arrays.fill(raw,bg);
        for(int[] xy:new int[][]{{7,4},{6,5},{7,5}})
            paintCell(raw,w,h,left,top,cell,xy[0],xy[1],oldYellow);
        paintCell(raw,w,h,left,top,cell,6,6,0xffef7929);
        Game.Image before=new Game.Image(w,h,raw);
        int[] latest=raw.clone();
        for(int i=0;i<piece.xs.length;i++)
            paintCell(latest,w,h,left,top,cell,
                6+piece.xs[i],4+piece.ys[i],heldYellow);
        Game.Image held=new Game.Image(w,h,latest);
        float fx=427.2708f,fy=1046.9375f;
        check(DragAlignment.locate(held,before,frame,shape,fx,fy)==null,
            "diag20 normal strict tracking rejects sprite over three old cells");
        DragAlignment.Origin found=DragAlignment.locateTemporalBoardOverlap(
            held,before,frame,shape,fx,fy);
        check(found!=null &&
            Math.abs(found.x-(left+6*cell))<=cell*.25f &&
            Math.abs(found.y-(top+4*cell))<=cell*.25f,
            "diag20 temporal tracker recovers four-cell yellow sprite");
        check(DragAlignment.locateTemporalBoardOverlap(before,before,frame,
            shape,fx,fy)==null,
            "diag20 unchanged board never masquerades as moving sprite");
        Game.Frame noBoard=new Game.Frame(1L<<(6*8+6),
            frame.shapes,left,top,cell,w,h,1);
        check(DragAlignment.locateTemporalBoardOverlap(held,before,
            noBoard,shape,fx,fy)==null,
            "diag20 stale-colored centers need independent saved occupancy");
        int[] staticGold=raw.clone();
        paintCell(staticGold,w,h,left,top,cell,6,6,heldYellow);
        check(DragAlignment.locateTemporalBoardOverlap(
            new Game.Image(w,h,staticGold),before,frame,shape,fx,fy)==null,
            "diag20 one new tile plus unchanged neighbors is not held motion");
        int[] broken=latest.clone();
        paintCell(broken,w,h,left,top,cell,6,6,bg);
        check(DragAlignment.locateTemporalBoardOverlap(
            new Game.Image(w,h,broken),before,frame,shape,fx,fy)==null,
            "diag20 all piece cells must visibly exist");
        float[] nudge=RecoveryNudge.plan(frame,fx,fy,0);
        check(nudge!=null&&nudge[1]<0&&nudge[0]==0,
            "diag20 occlusion fallback moves vertically while still holding");
        check(RecoveryNudge.plan(frame,fx,fy,2)==null,
            "no unbounded blind gestures permitted");
        check(RecoveryNudge.plan(frame,Float.NaN,fy,0)==null,
            "invalid pointer cannot trigger a blind stroke");
    }

    /**
     * Diagnostic (21), real file metadata: v1.4.18, phase=PICKED, 720x1560
     * one old blue board tile, five-cell gold tray at (359.5,1192).
     * before/probe/held differ ONLY in the score region: not one gameplay
     * pixel changed. An Android completed callback is not proof of pickup.
     */
    static void diagnostic21IgnoredPickupRegression() {
        final int width=720,height=1560,bg=0xff212852,blue=0xff4265de,
                  gold=0xffefbc30;
        final float left=41f,top=368f,cell=79.875f;
        Game.Piece longBar=new Game.Piece(0,0,0,1,0,2,0,3,0,4);
        Game.Shape piece=new Game.Shape(1,longBar,359.5f,1192f,gold);
        Game.Frame frame=new Game.Frame(1L<<16,
            java.util.Arrays.asList(piece),left,top,cell,width,height,1);
        int[] source=new int[width*height];
        java.util.Arrays.fill(source,bg);
        paintCell(source,width,height,left,top,cell,0,2,blue);
        for(int y=1092;y<1295;y++)for(int x=346;x<373;x++)
            source[y*width+x]=gold;
        Game.Image before=new Game.Image(width,height,source);
        int[] scoreOnly=source.clone();
        // Ignore even a massive digit animation when the game board,
        // selected tray and held sprite remain completely unchanged.
        for(int y=230;y<305;y++)for(int x=175;x<580;x++)
            scoreOnly[y*width+x]=0xffffffff;
        Game.Image ignored=new Game.Image(width,height,scoreOnly);
        check(!PickupControl.gameplayChanged(before,ignored,frame),
            "diag21 score animation alone cannot confirm a pickup");
        check(!PickupControl.gameplayChanged(before,before,frame),
            "diag21 identical gameplay frames cannot authorize a probe");
        float[] stage=PickupControl.stage(frame,piece,0);
        check(stage!=null && stage[1]<piece.pickY-cell &&
              stage[1]<top+8f*cell && Math.abs(stage[0]-piece.pickX)<2f,
            "diag21 stage must leave the selected tray UPWARDS");
        check(Math.abs(stage[1]-1366.4375f)>cell*3f,
            "diag21 previously wrong downward stage must not return");
        float[] second=PickupControl.stage(frame,piece,1);
        check(second!=null && second[1]<stage[1]-cell*.5f,
            "diag21 bounded second press uses a distinct higher route");
        int[] changed=scoreOnly.clone();
        for(int y=640;y<920;y++)for(int x=350;x<370;x++)
            changed[y*width+x]=gold;
        Game.Image lifted=new Game.Image(width,height,changed);
        check(PickupControl.gameplayChanged(before,lifted,frame),
            "a truly lifted sprite inside the board must be observable");
        ScoreSettler settle=new ScoreSettler();
        check(!settle.ready(before,1000L),
            "score settling requires at least one pair of observations");
        check(!settle.ready(before,1300L),
            "a still-animating score delays the next pickup");
        check(settle.ready(before,1700L),
            "a settled unchanged score lets the next pickup proceed");
        check(!settle.ready(ignored,1800L),
            "a real digit transition resets the score quiet period");
        check(settle.ready(ignored,2500L),
            "score quiet period eventually ends");
    }

    /** Diagnostic 18: no grid-preview coordinate can poison direct calibration. */
    static void diagnostic18ProxyCalibrationRegression() {
        check(DragCalibration.mayRecordVerifiedTargetSample(true,false),
            "strict physical target observations remain eligible for verified learning");
        check(!DragCalibration.mayRecordVerifiedTargetSample(false,false),
            "diagnostic18 blue diagonal preview is NOT a sprite-origin measurement");
        check(!DragCalibration.mayRecordVerifiedTargetSample(false,true),
            "all release-only grid proxies are excluded from motor calibration");
        check(!DragCalibration.mayRecordVerifiedTargetSample(true,true),
            "a previous uncertain recovery also blocks later sample learning");
    }

    /** Diagnostic 18: clear-row BLUE glow hides a two-cell diagonal. */
    static void diagnostic18BlueDiagonalLineClearRegression() {
        final int width=720,height=1560,blue=0xff4265de;
        final float left=41f,top=368f,cell=80f;
        final int col=2,row=2;
        final float tx=left+col*cell,ty=top+row*cell;
        final Game.Piece piece=new Game.Piece(1,0,0,1);
        final Game.Shape shape=new Game.Shape(0,piece,167.5f,1174f,blue);
        long board=0;
        for(int x=0;x<8;x++)if(x!=3)board|=1L<<(row*8+x);
        for(int x=2;x<8;x++)board|=1L<<(7*8+x);
        final Game.Frame frame=new Game.Frame(board,java.util.Arrays.asList(shape),
            left,top,cell,width,height,1);
        int[] original=new int[width*height];
        java.util.Arrays.fill(original,0xff212852);
        for(int x=0;x<8;x++)if(x!=3)
            paintDiagnostic18Tile(original,width,left,top,cell,x,row,
                x%3==0?0xffe5b531:(x%3==1?0xffc53939:0xffec781f));
        for(int x=2;x<8;x++)
            paintDiagnostic18Tile(original,width,left,top,cell,x,7,0xffdb6b30);
        Game.Image before=new Game.Image(width,height,original);
        int[] active=original.clone();
        for(int x=0;x<8;x++)
            paintDiagnostic18Tile(active,width,left,top,cell,x,row,blue);
        paintDiagnostic18Tile(active,width,left,top,cell,col,row+1,blue);
        Game.Image held=new Game.Image(width,height,active);
        // The uploaded 720x1560 diagnostic has RGB(58,89,222) blue row
        // glow, RGB(66,101,230) tile faces and RGB(49,85,222) bevels.
        // Test those actual sampled colors, not only ideal flat blue.
        int[] screenshotPalette=original.clone();
        for(int x=0;x<8;x++)
            paintDiagnostic18Tile(screenshotPalette,width,left,top,cell,
                x,row,0xff3a59de);
        paintDiagnostic18Tile(screenshotPalette,width,left,top,cell,
            col+1,row,0xff4265e6);
        paintDiagnostic18Tile(screenshotPalette,width,left,top,cell,
            col,row+1,0xff4265e6);
        check(DragAlignment.locateDiagonalLineClearPreview(
            new Game.Image(width,height,screenshotPalette),before,frame,
            shape,tx,ty,254f,904f)!=null,
            "diag18 realistic blue row+tail palette is a guarded release proxy");
        check(Rules.place(board,piece,col,row)!=null&&
              Rules.place(board,piece,col,row).lines==1,
              "diag18 requires legal exactly-one-row-clear input");
        check(DragAlignment.targetCellPreview(held,before,frame,shape,col,row),
              "diag18 two isolated target squares are newly blue");
        DragAlignment.Origin found=DragAlignment.locateDiagonalLineClearPreview(
            held,before,frame,shape,tx,ty,254f,904f);
        check(found!=null && Math.abs(found.x-tx)<1f &&
              Math.abs(found.y-ty)<1f,
              "diag18 strict isolated diagonal+full-row glow is a release-only proxy");
        int[] noTail=active.clone();
        paintDiagnostic18Tile(noTail,width,left,top,cell,col,row+1,0xff212852);
        check(DragAlignment.locateDiagonalLineClearPreview(
            new Game.Image(width,height,noTail),before,frame,shape,
            tx,ty,254f,904f)==null,
            "diag18 must reject a global blue row without isolated diagonal tail");
        int[] extraTail=active.clone();
        paintDiagnostic18Tile(extraTail,width,left,top,cell,col+1,row+1,blue);
        check(DragAlignment.locateDiagonalLineClearPreview(
            new Game.Image(width,height,extraTail),before,frame,shape,
            tx,ty,254f,904f)==null,
            "diag18 must reject extra independent blue cells beside diagonal");
        int[] noGlow=active.clone();
        paintDiagnostic18Tile(noGlow,width,left,top,cell,0,row,0xffe5b531);
        check(DragAlignment.locateDiagonalLineClearPreview(
            new Game.Image(width,height,noGlow),before,frame,shape,
            tx,ty,254f,904f)==null,
            "diag18 must reject new target tiles without full-row clear animation");
        check(DragAlignment.locateDiagonalLineClearPreview(
            held,before,frame,shape,tx,ty,650f,1250f)==null,
            "diag18 unrelated finger positions cannot authorize release");
        Game.Shape wrong=new Game.Shape(0,new Game.Piece(0,0,1,1),167f,1174f,blue);
        check(DragAlignment.locateDiagonalLineClearPreview(
            held,before,frame,wrong,tx,ty,254f,904f)==null,
            "diag18 only exact reverse diagonal gets line-glow proxy");
    }
    static void paintDiagnostic18Tile(int[] pixels,int width,
            float left,float top,float cell,int col,int row,int color) {
        int x0=Math.round(left+col*cell+3),x1=Math.round(left+(col+1)*cell-3);
        int y0=Math.round(top+row*cell+3),y1=Math.round(top+(row+1)*cell-3);
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++)
            pixels[y*width+x]=color;
    }

    public static void main(String[] args) throws Exception {
        diagnostic21IgnoredPickupRegression();
        diagnostic20TemporalOcclusionRegression();
        diagnostic19RedLTwoOldBoardRegression();
        diagnostic18ProxyCalibrationRegression();
        diagnostic18BlueDiagonalLineClearRegression();
        predictiveHeldRoiRegression();
        diagnostic17PurpleShiftedBoardOverlap();
        diagnostic16OrangeDarkBevelRegression();
        diagnostic15BlueLineGlowRegression();
        diagnostic14ComboGlowRegression();
        diagnostic13OccupiedGreenBoardRegression();
        diagnostic11StackedPreviewRegression();
        pixelSamplerGeometryRegression();
        frameReuseSafetyRegression();
        cachedGridRecognitionRegression();
        diagnostic9ExactShapeAndEdgeRegression();        successGatedSpeedRegression();
        measuredSpriteResponseRegression();

        verticalFiveSelectedTrayOverlapRegression();        verifiedDragCalibrationRegression();
        adaptiveServoComparison();
        thumbnailOverlapPickedMotionRegression();
        verticalFiveBottomOverlayRegression();
        rightOfFingerPickupRegression();
        statusOverlayOcclusionRegression();
        boundedDraggedPointerRegression();

        snappedTargetPreviewRegression();
        alignRecoveryBehindOverlay();
        comboCaptionDetection();
        highScorePlanning();
        guideOverlaySanitization();
        brownThemeGeometry();
        blueThemeGeometry();
        diagnosticArchive();
        highLift();
        mergedOuterFaces();
        purpleFrames();
        diagnostics();
        darkPalette();
        recognition();
        planning();
        alignment();
        variants();
        simulations();
        pixelBuffers();
        ambiguousFrames();
        bridgeAlignment();
        System.out.println("PASS "+checks+" core checks");
    }
}
