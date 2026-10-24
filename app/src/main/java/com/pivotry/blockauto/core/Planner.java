package com.pivotry.blockauto.core;
import java.util.*;

/** Score-first, full-tray-safe Block Blast Classic planner.
 * Exact mechanics: each placement clears full rows and columns together.
 * Scoring is an Android 10.8.1 unmodified-Classic approximation; active
 * game variants may differ. Unknown next trays cannot be made perfectly safe.
 */
public final class Planner {
    private Planner() {}
    public static final class Move {
        public final int piece,x,y;
        Move(int i,int x,int y){piece=i;this.x=x;this.y=y;}
    }
    public static final class Result {
        public final List<Move> moves;public final double score;
        Result(List<Move> m,double s){moves=m;score=s;}
    }
    private static final class State {
        final long board;final int used,combo,misses,depth,clears;
        final List<Move> moves;final double reward,rank;
        State(long b,int u,List<Move> m,double points,int c,int misses,int clears){
            board=b;used=u;moves=m;reward=points;combo=c;this.misses=misses;
            this.clears=clears;depth=m.size();
            rank=points+quality(b)+survival(b);
        }
    }
    // Variant-dependent, but the confirmed baseline uses 10*C for one or two
    // lines, and 10*C*L*(L-1) for >=3 simultaneous lines.
    public static long linePoints(int lines,int combo) {
        if(lines<=0||combo<=0)return 0;
        return 10L*combo*(lines<=2?lines:lines*(lines-1));
    }
    private static int[] flexibility(long b) {
        int square3=0,horiz5=0,vert5=0;
        for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
            if(x<=5&&y<=5&&(b&(0x70707L<<(8*y+x)))==0)square3++;
            if(x<=3&&(b&(31L<<(8*y+x)))==0)horiz5++;
            if(y<=3&&(b&(0x0101010101L<<(8*y+x)))==0)vert5++;
        }
        return new int[]{square3,horiz5,vert5};
    }
    static double survival(long b) {
        int[] f=flexibility(b);
        int empty=64-Long.bitCount(b);
        double penalty=0;
        if(f[0]==0)penalty+=900;
        else if(f[0]==1)penalty+=120;
        if(f[1]==0)penalty+=180;
        if(f[2]==0)penalty+=180;
        if(empty<18)penalty+=(18-empty)*35.0;
        else if(empty<28)penalty+=(28-empty)*6.0;
        return -penalty;
    }
    static double quality(long b) {
        int empty=64-Long.bitCount(b),open2=0,open3=0,isolated=0,lines=0;
        for(int y=0;y<8;y++)for(int x=0;x<8;x++)if((b&(1L<<(8*y+x)))==0) {
            int neighbors=0;
            if(x>0&&(b&(1L<<(8*y+x-1)))==0)neighbors++;
            if(x<7&&(b&(1L<<(8*y+x+1)))==0)neighbors++;
            if(y>0&&(b&(1L<<(8*y+x-8)))==0)neighbors++;
            if(y<7&&(b&(1L<<(8*y+x+8)))==0)neighbors++;
            if(neighbors==0)isolated++;
            if(x<7&&y<7&&(b&(0x303L<<(8*y+x)))==0)open2++;
            if(x<6&&y<6&&(b&(0x70707L<<(8*y+x)))==0)open3++;
        }
        for(int i=0;i<8;i++) {
            int row=(int)(b>>>(8*i))&255,col=0;
            for(int y=0;y<8;y++)if((b&(1L<<(8*y+i)))!=0)col|=1<<y;
            for(int pattern:new int[]{row,col})for(int n=3;n<=5;n++)for(int x=0;x<=8-n;x++)
                if((pattern&(((1<<n)-1)<<x))==0)lines++;
        }
        int[] f=flexibility(b);
        return empty*1.25+open2*.6+open3*1.4+lines*.12-isolated*18+
            Math.min(8,f[0])*12+Math.min(5,f[1])*4+Math.min(5,f[2])*4;
    }
    private static double objective(State s) {
        // First protect the ability to accept common future large pieces.
        // Within that safety envelope, score a clear at the ACTUAL chain
        // level, not at an artificial cap of 20.
        return s.rank;
    }
    public static Result best(long board,List<Game.Piece> pieces,int milliseconds) {
        return best(board,pieces,milliseconds,0,0);
    }
    /** Current internal clearing count and placements since last clear. */
    public static Result best(long board,List<Game.Piece> pieces,int milliseconds,int chain,int misses) {
        if(pieces.isEmpty()||pieces.size()>3)return null;
        int initialCombo=Math.max(0,Math.min(100000,chain));
        int initialMisses=Math.max(0,Math.min(2,misses));
        long deadline=System.nanoTime()+Math.max(5,milliseconds)*1_000_000L;
        List<State> beam=Collections.singletonList(
            new State(board,0,Collections.emptyList(),0,initialCombo,initialMisses,0));
        State partial=null,full=null;
        int expanded=0;
        for(int depth=0;depth<pieces.size();depth++) {
            Map<String,State> unique=new HashMap<>();
            boolean exhausted=false;
            outer:for(State s:beam)for(int i=0;i<pieces.size();i++)if((s.used&(1<<i))==0) {
                Game.Piece piece=pieces.get(i);
                for(int y=0;y<=8-piece.h;y++)for(int x=0;x<=8-piece.w;x++) {
                    if((expanded++&127)==0&&(expanded>90000||System.nanoTime()>deadline)){
                        exhausted=true;break outer;
                    }
                    Rules.Placement p=Rules.place(s.board,piece,x,y);
                    if(p==null)continue;
                    int nextMisses=p.lines>0?0:s.misses+1;
                    int nextCombo=p.lines>0?s.combo+1:(nextMisses>=3?0:s.combo);
                    double points=piece.xs.length+linePoints(p.lines,nextCombo);
                    if(p.lines>0 && p.board==0)points+=150; // conservative all-clear potential; round unknown
                    // Breaking a large established chain is catastrophic
                    // for subsequent scoring, even if its immediate gain is 0.
                    double broken=(s.combo>0 && nextCombo==0)?50.0*s.combo:0;
                    List<Move> moves=new ArrayList<>(s.moves);moves.add(new Move(i,x,y));
                    State n=new State(p.board,s.used|(1<<i),moves,
                        s.reward+points-broken,nextCombo,Math.min(3,nextMisses),s.clears+(p.lines>0?1:0));
                    String key=p.board+":"+n.used+":"+n.combo+":"+n.misses;
                    State old=unique.get(key);
                    if(old==null||objective(n)>objective(old))unique.put(key,n);
                    if(partial==null||n.depth>partial.depth||
                            (n.depth==partial.depth&&objective(n)>objective(partial)))partial=n;
                    if(n.depth==pieces.size()&&(full==null||objective(n)>objective(full)))full=n;
                }
            }
            if(unique.isEmpty())break;
            beam=new ArrayList<>(unique.values());
            beam.sort((a,b)->Double.compare(objective(b),objective(a)));
            if(beam.size()>256)beam=new ArrayList<>(beam.subList(0,256));
            if(exhausted)break;
        }
        // If the bounded high-score beam never reached a full hand, run a
        // narrow, ordered feasibility search before accepting partial play.
        // This prevents search timeouts/pruning from masquerading as a blocked
        // tray. It does not assert anything about the unknown NEXT tray.
        if(full==null && pieces.size()>1) {
            State fallback=completeTray(board,pieces,initialCombo,initialMisses);
            if(fallback!=null)full=fallback;
        }
        // The solver NEVER intentionally prefers a partial line-clear route
        // over a full-hand route. The next tray is hidden, so future survival
        // cannot be promised mathematically.
        State chosen=full!=null?full:partial;
        return chosen==null?null:new Result(Collections.unmodifiableList(chosen.moves),objective(chosen));
    }
    /** Find any complete legal hand independent of scoring beam width.
     * At most three pieces exist; memoize (board, used) dead ends.
     */
    private static State completeTray(long board,List<Game.Piece> pieces,int chain,int misses) {
        Set<String> failed=new HashSet<>();
        return completeFrom(new State(board,0,Collections.emptyList(),0,chain,misses,0),
            pieces,(1<<pieces.size())-1,failed);
    }
    private static State completeFrom(State s,List<Game.Piece> pieces,int all,Set<String> failed) {
        if(s.used==all)return s;
        String key=s.board+":"+s.used;
        if(failed.contains(key))return null;
        for(int i=0;i<pieces.size();i++)if((s.used&(1<<i))==0) {
            Game.Piece piece=pieces.get(i);
            for(int y=0;y<=8-piece.h;y++)for(int x=0;x<=8-piece.w;x++) {
                Rules.Placement p=Rules.place(s.board,piece,x,y);
                if(p==null)continue;
                int nextMisses=p.lines>0?0:s.misses+1;
                int nextCombo=p.lines>0?s.combo+1:(nextMisses>=3?0:s.combo);
                double points=piece.xs.length+linePoints(p.lines,nextCombo);
                if(p.lines>0&&p.board==0)points+=150;
                double broken=(s.combo>0&&nextCombo==0)?50.0*s.combo:0;
                List<Move> moves=new ArrayList<>(s.moves);
                moves.add(new Move(i,x,y));
                State child=new State(p.board,s.used|(1<<i),moves,
                    s.reward+points-broken,nextCombo,Math.min(3,nextMisses),
                    s.clears+(p.lines>0?1:0));
                State completed=completeFrom(child,pieces,all,failed);
                if(completed!=null)return completed;
            }
        }
        failed.add(key);
        return null;
    }

}
