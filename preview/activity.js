function element(tag,className,text){const value=document.createElement(tag);if(className)value.className=className;if(text!==undefined)value.textContent=text;return value;}
export function createActivity({screen,onClose}){
  let tab='schedules';
  function render(){
    screen.replaceChildren();const header=element('header','settings-header');
    const back=element('button','icon-button');back.type='button';back.setAttribute('aria-label','Back to chat');back.onclick=close;
    const arrow=document.createElementNS('http://www.w3.org/2000/svg','svg');arrow.setAttribute('viewBox','0 0 24 24');arrow.setAttribute('aria-hidden','true');const path=document.createElementNS(arrow.namespaceURI,'path');path.setAttribute('d','m14 6-6 6 6 6');arrow.append(path);back.append(arrow);
    const title=element('h1','settings-title','Activity');title.tabIndex=-1;header.append(back,title);
    const tabs=element('nav','activity-tabs');tabs.setAttribute('aria-label','Activity sections');
    for(const [id,label] of [['schedules','Schedules'],['workers','Subagents'],['displays','Displays']]){const button=element('button','',label);button.type='button';button.setAttribute('aria-current',String(tab===id));button.onclick=()=>{tab=id;render();};tabs.append(button);}
    const body=element('div','settings-body');const state=element('div','activity-device-state','Phone activity unavailable · Android app required');body.append(state);
    const heading=element('h2','activity-heading',({schedules:'Work that runs later.',workers:'A little help in parallel.',displays:'Room for phone tasks.'})[tab]);body.append(heading);
    if(tab==='schedules'){
      body.append(element('p','settings-description','Manage scheduled phone tasks in the Android app.'));
      const card=element('section','activity-info-card');card.append(element('h3','','Upcoming schedules'),element('p','','No phone schedule data is available here. Open Pocket Agent on Android to view the next run or pause a schedule.'));body.append(card);
      const history=element('section','activity-info-card');history.append(element('h3','','Recent runs'),element('p','','Run results are stored on your phone. Completed runs show a short summary, with details available separately.'));body.append(history);
    }else if(tab==='workers'){
      body.append(element('p','settings-description','On Android, delegated work stays visible in the conversation while you keep talking.'));
      const card=element('section','activity-info-card');card.append(element('h3','','Worker activity unavailable'),element('p','','Open the Android app to see delegated tasks. Each chat card shows the task name and status, with a way to open, steer, or stop the worker.'));body.append(card);
      const display=element('section','activity-info-card');display.append(element('h3','','Display status stays with the task'),element('p','','If a worker uses a phone display, its card shows Preparing, Waiting, In use, or Releasing. Display IDs appear only when assigned by Android.'));body.append(display);
    }else{
      body.append(element('p','settings-description','The Android runtime shares up to three virtual displays between tasks. Extra tasks wait until a slot is released.'));
      const pool=element('div','display-slots');for(let i=1;i<=3;i++){const slot=element('div','display-slot');slot.append(element('span','','Slot '+i),element('small','','No phone connection'));pool.append(slot);}body.append(pool);
      body.append(element('p','settings-native-note','Virtual displays require Shizuku in the Android app. Visible-screen tasks can use Accessibility instead. The browser does not allocate displays or control your phone.'));
    }
    body.append(element('p','settings-preview-note','Phone tasks run in Pocket Agent on Android.'));
    screen.append(header,tabs,body);title.focus({preventScroll:true});
  }
  function close(){screen.hidden=true;onClose();}
  screen.addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();close();}});
  return {open(){screen.hidden=false;render();}};
}
