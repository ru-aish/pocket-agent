import {api} from './connection.js';
export function createVoice({button,feedback,status,audio,onTranscript,getConfig,openSettings,cancelResponse,paint}){
  let recorder,recognition,media,chunks=[],phase='idle',generation=0,controller,timer,audioUrl;
  const cancelButton=feedback.querySelector('button');
  const setupButton=document.createElement('button');setupButton.type='button';setupButton.className='connection-button';setupButton.textContent='Voice settings';setupButton.onclick=()=>{cancel();openSettings('voice');};feedback.append(setupButton);
  function release(){clearTimeout(timer);media?.getTracks().forEach(track=>track.stop());media=undefined;}
  function setPhase(value,message=''){
    phase=value;feedback.hidden=value==='idle';status.textContent=message;button.dataset.state=value;
    button.setAttribute('aria-label',value==='recording'?'Finish voice input':value==='idle'?'Start voice input':'Stop voice');
    button.setAttribute('aria-pressed',String(value==='recording'));button.querySelector('[data-icon]').dataset.icon=value==='idle'?'mic':'stop';paint(button);
  }
  function cancel(){
    generation++;controller?.abort();recognition?.abort();recognition=undefined;
    if(recorder?.state==='recording')recorder.stop();release();chunks=[];audio.pause();audio.removeAttribute('src');audio.hidden=true;
    window.speechSynthesis?.cancel();if(audioUrl)URL.revokeObjectURL(audioUrl);audioUrl=undefined;setPhase('idle');
  }
  function failure(message){setPhase('idle');feedback.hidden=false;status.textContent=message;}
  cancelButton.onclick=cancel;
  audio.onended=()=>{if(audioUrl)URL.revokeObjectURL(audioUrl);audioUrl=undefined;audio.hidden=true;setPhase('idle');};
  async function systemSpeech(selected){
    const Recognition=window.SpeechRecognition||window.webkitSpeechRecognition;
    if(!Recognition){failure('System speech is unavailable in this browser. Choose Groq Whisper or Deepgram in Voice settings.');return;}
    recognition=new Recognition();recognition.lang=navigator.language||'en-US';recognition.continuous=false;recognition.interimResults=true;
    let heard='',failed=false;
    recognition.onresult=event=>{if(selected!==generation)return;let interim='';const finals=[];for(let i=0;i<event.results.length;i++){const result=event.results[i];if(result.isFinal)finals.push(result[0].transcript);else interim+=result[0].transcript;}heard=finals.join(' ');status.textContent=interim||heard||'Listening…';};
    recognition.onerror=event=>{if(selected!==generation)return;failed=true;failure(({ 'not-allowed':'Microphone permission was denied. Allow it in your browser to use voice.',network:'System speech could not connect. Choose Groq Whisper or Deepgram, or try again.','no-speech':'No speech detected. Try again.'})[event.error]||'System speech is unavailable. Choose another engine in Voice settings.');};
    recognition.onend=()=>{if(selected!==generation)return;recognition=undefined;if(heard.trim()){onTranscript(heard.trim());setPhase('idle');}else if(!failed)failure('No speech detected. Try again.');};
    recognition.start();setPhase('recording','Listening… Tap the microphone again when you’re done.');
  }
  button.onclick=async()=>{
    if(phase==='recording'){if(recognition)recognition.stop();else if(recorder?.state==='recording')recorder.stop();return;}
    if(phase!=='idle'){cancel();return;}
    const selected=++generation,engine=getConfig().stt;
    try{
      cancelResponse();
      if(engine==='android'||!['groq','deepgram'].includes(engine)){await systemSpeech(selected);return;}
      setPhase('preparing','Checking voice connection…');
      const connection=await api('/api/provider/status');if(selected!==generation)return;
      if(!connection.voice[engine]){cancel();openSettings('voice');return;}
      if(!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder)throw Error('This browser cannot record audio. Use the Android app for voice.');
      setPhase('preparing','Allow microphone access to start speaking.');
      const source=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true}});
      if(selected!==generation){source.getTracks().forEach(track=>track.stop());return;}
      media=source;chunks=[];let size=0;
      const mimeType=['audio/webm;codecs=opus','audio/ogg;codecs=opus','audio/mp4'].find(type=>MediaRecorder.isTypeSupported(type));
      recorder=new MediaRecorder(media,mimeType?{mimeType}:{});
      recorder.ondataavailable=event=>{if(selected!==generation)return;if(event.data.size){size+=event.data.size;if(size>10*1024*1024){cancel();failure('Recording was too large. Try a shorter message.');return;}chunks.push(event.data);}};
      recorder.onerror=()=>{if(selected!==generation)return;cancel();failure('Microphone recording failed. Try again.');};
      recorder.onstop=async()=>{
        if(selected!==generation)return;release();
        try{
          controller=new AbortController();setPhase('transcribing',engine==='groq'?'Transcribing with Groq Whisper…':'Transcribing with Deepgram…');
          const recording=new Blob(chunks,{type:recorder.mimeType});chunks=[];
          const response=await fetch('/api/voice/transcribe',{method:'POST',headers:{'X-Pocket-Request':'1','X-Pocket-Speech-Engine':engine,'Content-Type':recording.type},body:recording,signal:controller.signal});
          const result=await response.json();if(!response.ok)throw Error(result.error||'Voice transcription failed.');if(selected!==generation)return;
          onTranscript(result.text);setPhase('idle');
        }catch(error){if(selected===generation)failure(error.name==='AbortError'?'Voice cancelled.':error.message);}
      };
      recorder.start(500);setPhase('recording','Listening… Tap the microphone again when you’re done.');timer=setTimeout(()=>{if(selected!==generation)return;if(recorder.state==='recording')recorder.stop();},30000);
    }catch(error){release();if(selected===generation)failure(error.name==='NotAllowedError'?'Microphone access was denied. Allow it in your browser to use voice.':error.message);}
  };
  async function speak(text){
    cancel();const selected=generation;setPhase('speaking','Preparing spoken reply…');
    try{
      if(getConfig().stt!=='deepgram'){
        if(!window.speechSynthesis)throw Error('System speech playback is unavailable in this browser.');
        const utterance=new SpeechSynthesisUtterance(text);utterance.onend=()=>{if(selected===generation)setPhase('idle');};utterance.onerror=()=>{if(selected===generation)failure('Speech playback failed.');};
        status.textContent='Reading the reply aloud';window.speechSynthesis.speak(utterance);return;
      }
      controller=new AbortController();const response=await fetch('/api/voice/speak',{method:'POST',headers:{'X-Pocket-Request':'1','Content-Type':'application/json'},body:JSON.stringify({text}),signal:controller.signal});
      if(!response.ok){const result=await response.json();throw Error(result.error||'Spoken reply is unavailable.');}
      const blob=await response.blob();if(selected!==generation)return;audioUrl=URL.createObjectURL(blob);audio.src=audioUrl;audio.hidden=false;status.textContent='Reading the reply aloud';
      try{await audio.play();}catch{if(selected===generation)status.textContent='Tap play to hear the reply.';}
    }catch(error){if(selected===generation)failure(error.message);}
  }
  window.addEventListener('pagehide',cancel);
  return {cancel,speak};
}
