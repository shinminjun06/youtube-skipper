package com.pivotry.blockauto.core;

/**
 * Conservative, success-gated latency reduction for the Android gesture
 * controller. Only the speed of an already-verified placement changes:
 * pickup, held-shape identity, target cells, board and consumed slot are
 * checked exactly the same way as in v1.4.2.
 *
 * Cold start uses the proven 1.4.2 timings. Two consecutive clean, verified
 * placements unlock a moderate speed tier; five unlock the faster tier.
 * Any unstable measurement, extra correction or gesture failure immediately
 * restores the known baseline. Never use an Android dispatch callback alone
 * as proof of a successful placement.
 */
public final class AdaptivePace {
    public enum Tier { BASE, QUICK, FAST }
    private int cleanPlacements=0;
    public void reset(){cleanPlacements=0;}
    public void uncertainty(){cleanPlacements=0;}
    /** Called ONLY after MoveVerifier.matches(frame) on a stable board. */
    public void verified(int corrections,int gestureCount){
        if(corrections<0||corrections>2||gestureCount<2||gestureCount>6){
            uncertainty();
            return;
        }
        cleanPlacements=Math.min(cleanPlacements+1,8);
    }
    public Tier tier(){
        return cleanPlacements>=5?Tier.FAST:
            cleanPlacements>=2?Tier.QUICK:Tier.BASE;
    }
    public int cleanPlacements(){return cleanPlacements;}
    // Pickup dwell remains 160ms at every speed, for compatibility with
    // Android accessibility and the game's press-to-grab threshold.
    public int pickupHoldMs(){return 160;}
    // Longer distances still get a longer path to avoid game animation
    // catching up after callback completion. Never accelerate beyond 335ms.
    public int firstTravelMs(){
        return tier()==Tier.BASE?500:tier()==Tier.QUICK?405:345;
    }
    public int probeMs(){
        return tier()==Tier.BASE?320:tier()==Tier.QUICK?265:225;
    }
    public int retryMs(){
        return tier()==Tier.BASE?350:tier()==Tier.QUICK?305:275;
    }
    public int correctionMs(){
        return tier()==Tier.BASE?350:tier()==Tier.QUICK?295:260;
    }
    public int releaseMs(){
        return tier()==Tier.BASE?150:tier()==Tier.QUICK?140:130;
    }
    public int postGestureReadMs(){
        return tier()==Tier.BASE?160:tier()==Tier.QUICK?130:115;
    }
    public int framePollMs(){
        return tier()==Tier.BASE?130:tier()==Tier.QUICK?105:90;
    }
    public int boardStableMs(){
        return tier()==Tier.BASE?200:tier()==Tier.QUICK?185:175;
    }
    public int heldStableMs(){
        return tier()==Tier.BASE?180:tier()==Tier.QUICK?165:150;
    }
}
