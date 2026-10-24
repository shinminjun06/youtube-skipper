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
    public static void main(String[] args) throws Exception {
        recognition();
        planning();
        alignment();
        variants();
        simulations();
        pixelBuffers();
        ambiguousFrames();
        System.out.println("PASS "+checks+" core checks");
    }
}
