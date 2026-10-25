/* Experimental Cocos JS VM diagnostic. Not injected or validated in-game. */
(function(root) {
  'use strict';
  var re=/board|grid|cell|piece|block|shape|tile|matrix|slot|tray|map|square/i;
  function dump(v,d) {
    if (d>2 || v==null) return null;
    if (typeof v==='number' || typeof v==='boolean') return v;
    if (typeof v==='string') return v.slice(0,100);
    if (Array.isArray(v)) return v.length<=64 ? v.map(function(x){return dump(x,d+1);}) : {count:v.length};
    if (typeof v==='object') return {keys:Object.keys(v).slice(0,12)};
    return null;
  }
  function sample() {
    if(!root.cc || !cc.director || !cc.director.getScene) return {status:'COCOS_NOT_READY'};
    var scene=cc.director.getScene();
    if(!scene) return {status:'NO_SCENE'};
    var queue=[{n:scene,depth:0,path:'scene'}], hints=[],count=0;
    while(queue.length && count++<2000 && hints.length<120) {
      var cur=queue.shift(),n=cur.n, comps=n._components || [];
      for(var i=0;i<comps.length;i++) {
        var o=comps[i]; if(!o)continue;
        var keys=[];try{keys=Object.keys(o).slice(0,60);}catch(_e){}
        for(var j=0;j<keys.length && hints.length<120;j++) {
          var k=keys[j],v; if(!k || k.charAt(0)==='_')continue;
          try{v=o[k];}catch(_err){continue;}
          if(re.test(k)||(Array.isArray(v) && [3,8,64].indexOf(v.length)!==-1))
            hints.push({node:cur.path,component:o.constructor && o.constructor.name || 'Component',field:k,value:dump(v,0)});
        }
      }
      if(cur.depth>=12)continue;
      var children=n.children||n._children||[];
      for(var x=0;x<children.length;x++)queue.push({n:children[x],depth:cur.depth+1,path:cur.path+'/'+String(children[x].name||x)});
    }
    return {status:'OK',scene:String(scene.name||''),nodes:count,hints:hints};
  }
  root.BlockBlastInternalProbe={
    sample:sample,
    save:function(){
      var data=JSON.stringify(sample(),null,2);
      if(root.jsb && jsb.fileUtils && jsb.fileUtils.getWritablePath && jsb.fileUtils.writeStringToFile){
        var path=jsb.fileUtils.getWritablePath()+'blockblast_internal_probe.json';
        jsb.fileUtils.writeStringToFile(data,path);return path;
      }
      if(root.console && console.log)console.log(data);
      return data;
    }
  };
})(typeof globalThis!=='undefined'?globalThis:this);
