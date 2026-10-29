export function attachDrawerSwipe({workspace,isOpen,canOpen,open,close}){
  let gesture=null,suppressClickUntil=0,frame=0;
  function paint(){frame=0;if(!gesture?.dragging)return;workspace.style.setProperty('--drawer-offset',gesture.offset+'px');workspace.style.setProperty('--drawer-opacity',String(gesture.offset/gesture.width));}
  const drawer=workspace.querySelector('.chat-drawer');
  function finish(cancelled=false){
    if(!gesture)return;
    const current=gesture;gesture=null;if(frame){cancelAnimationFrame(frame);frame=0;}
    if(!current.dragging)return;
    const moved=current.offset-(current.wasOpen?current.width:0);
    const speed=moved/Math.max(1,performance.now()-current.started);
    const commit=!cancelled&&(Math.abs(moved)>current.width*.28||(Math.abs(moved)>24&&Math.abs(speed)>.45));
    const next=commit?!current.wasOpen:current.wasOpen;
    workspace.classList.remove('drawer-dragging');
    if(next){if(!isOpen())open();}else close();
    workspace.style.removeProperty('--drawer-offset');workspace.style.removeProperty('--drawer-width');workspace.style.removeProperty('--drawer-opacity');
    suppressClickUntil=performance.now()+350;
  }
  workspace.addEventListener('touchstart',event=>{
    if(event.touches.length!==1){finish(true);return;}
    if(event.target.closest('input,textarea,select,[contenteditable=true]')||window.getSelection()?.toString())return;
    const wasOpen=isOpen();if(!wasOpen&&!canOpen())return;
    const touch=event.touches[0];gesture={x:touch.clientX,y:touch.clientY,started:performance.now(),wasOpen,dragging:false,width:Math.min(320,workspace.clientWidth-48),offset:0};
  },{passive:true});
  workspace.addEventListener('touchmove',event=>{
    if(!gesture)return;
    if(event.touches.length!==1){finish(true);return;}
    const dx=event.touches[0].clientX-gesture.x,dy=event.touches[0].clientY-gesture.y;
    if(!gesture.dragging){
      if(Math.abs(dy)>10&&Math.abs(dy)>=Math.abs(dx)){gesture=null;return;}
      if(Math.abs(dx)<10||Math.abs(dx)<Math.abs(dy)*1.3)return;
      if((gesture.wasOpen&&dx>0)||(!gesture.wasOpen&&dx<0)){gesture=null;return;}
      gesture.dragging=true;if(!gesture.wasOpen)open();
      gesture.width=drawer.getBoundingClientRect().width;
      workspace.style.setProperty('--drawer-width',gesture.width+'px');workspace.classList.add('drawer-dragging');
    }
    event.preventDefault();
    gesture.offset=Math.max(0,Math.min(gesture.width,(gesture.wasOpen?gesture.width:0)+dx));
    if(!frame)frame=requestAnimationFrame(paint);
  },{passive:false});
  workspace.addEventListener('touchend',()=>finish(),{passive:true});
  workspace.addEventListener('touchcancel',()=>finish(true),{passive:true});
  workspace.addEventListener('click',event=>{if(performance.now()<suppressClickUntil){event.preventDefault();event.stopImmediatePropagation();}},{capture:true});
}
