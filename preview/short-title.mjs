export function shortTitle(value){
  let text=String(value||'').replace(/<[^>]*>/g,'').replace(/[\r\n\t]+/g,' ').replace(/^[\s#*"'`]+|[\s"'`.!]+$/g,'').replace(/^(?:title|conversation title)\s*:\s*/i,'').replace(/^[\s#*"'`]+|[\s*"'`.!]+$/g,'').replace(/\s+/g,' ').trim();
  const words=text.split(' ');if(words.length>6)text=words.slice(0,6).join(' ');
  if(Array.from(text).length>42)text=Array.from(text).slice(0,41).join('').trimEnd()+'…';
  return text||'New conversation';
}
