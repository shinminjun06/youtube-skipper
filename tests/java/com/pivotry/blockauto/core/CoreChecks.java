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

    public static void main(String[] args) throws Exception {        verticalFiveBottomOverlayRegression();
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
