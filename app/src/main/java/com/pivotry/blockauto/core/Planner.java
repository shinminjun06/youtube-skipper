package com.pivotry.blockauto.core;
import java.util.*;
public final class Planner {
    private Planner() {}
    public static final class Move {public final int piece,x,y;Move(int i,int x,int y){piece=i;this.x=x;this.y=y;}}
    public static final class Result {public final List<Move> moves;public final double score;Result(List<Move> m,double s){moves=m;score=s;}}
    private static final class State {
        final long board;final int used;final List<Move> moves;final double reward,value;final int combo,misses;
        State(long b,int u,List<Move> m,double reward,int combo,int misses){board=b;used=u;moves=m;this.reward=reward;this.combo=combo;this.misses=misses;value=reward+quality(b);}
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
        // Long empty runs are future legal placements for bars, not just free area.
        for(int i=0;i<8;i++) {
            int row=(int)(b>>>(8*i))&255,col=0;
            for(int y=0;y<8;y++)if((b&(1L<<(8*y+i)))!=0)col|=1<<y;
            for(int pattern:new int[]{row,col})for(int n=3;n<=5;n++)for(int x=0;x<=8-n;x++)
                if((pattern&(((1<<n)-1)<<x))==0)lines++;
        }
        int square3=0,horizontal5=0,vertical5=0;
        for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
            if(x<=5&&y<=5&&(b&(0x70707L<<(8*y+x)))==0)square3++;
            if(x<=3&&(b&(31L<<(8*y+x)))==0)horizontal5++;
            if(y<=3&&(b&(0x0101010101L<<(8*y+x)))==0)vertical5++;
        }
        double largeAccess=Math.min(4,square3)*18.0+
            Math.min(4,horizontal5)*5.0+Math.min(4,vertical5)*5.0;
        return empty*1.8+open2*.75+open3*2.8+lines*.14-isolated*12+
            largeAccess+(square3==0?-36.0:0.0);
    }
    /** Bounded beam over all visible orders and legal placements, replanned per move. */
    public static Result best(long board,List<Game.Piece> pieces,int milliseconds){
        if(pieces.isEmpty()||pieces.size()>3)return null;
        long deadline=System.nanoTime()+Math.max(5,milliseconds)*1_000_000L;
        List<State> beam=Collections.singletonList(new State(board,0,Collections.emptyList(),0,0,0));
        State best=null;int expanded=0;
        for(int depth=0;depth<pieces.size();depth++) {
            Map<String,State> unique=new HashMap<>();boolean exhausted=false;
            outer:for(State s:beam)for(int i=0;i<pieces.size();i++)if((s.used&(1<<i))==0) {
                Game.Piece piece=pieces.get(i);
                for(int y=0;y<=8-piece.h;y++)for(int x=0;x<=8-piece.w;x++) {
                    if((expanded++&63)==0&&(System.nanoTime()>deadline||expanded>30000)) {exhausted=true;break outer;}
                    Rules.Placement p=Rules.place(s.board,piece,x,y);if(p==null)continue;
                    List<Move> moves=new ArrayList<>(s.moves);moves.add(new Move(i,x,y));
                    // Classic-style combo survives two setup placements. A clear
                    // advances the chain once, even when it removes many lines.
                    int combo=p.lines>0?s.combo+1:(s.misses==2?0:s.combo);
                    int misses=p.lines>0?0:Math.min(3,s.misses+1);
                    double lineReward=p.lines*(80.0+35.0*Math.max(0,combo-1))+22.0*p.lines*p.lines;
                    // Preserve a large combo across setup moves; avoid three misses.
                    double maintain=p.lines>0?35.0*Math.min(combo,20):0;
                    double breakPenalty=p.lines==0&&misses==3&&s.combo>0?160.0+35.0*Math.min(s.combo,20):0;
                    State n=new State(p.board,s.used|(1<<i),moves,
                        s.reward+piece.xs.length+lineReward+maintain-breakPenalty,combo,misses);
                    String key=Long.toHexString(n.board)+":"+n.used+":"+n.combo+":"+n.misses;
                    State old=unique.get(key);if(old==null||n.value>old.value)unique.put(key,n);
                    if(best==null||n.moves.size()>best.moves.size()||n.moves.size()==best.moves.size()&&n.value>best.value)best=n;
                }
            }
            if(unique.isEmpty())break;
            beam=new ArrayList<>(unique.values());beam.sort((a,c)->Double.compare(c.value,a.value));
            if(beam.size()>128)beam=new ArrayList<>(beam.subList(0,128));
            if(exhausted)break;
        }
        return best==null?null:new Result(Collections.unmodifiableList(best.moves),best.value);
    }
}
