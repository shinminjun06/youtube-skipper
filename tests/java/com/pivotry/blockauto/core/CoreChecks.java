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
        System.out.println("PASS "+checks+" core checks");
    }
}
