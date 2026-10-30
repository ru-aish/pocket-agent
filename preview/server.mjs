import http from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { watch } from 'node:fs';
import { dirname, join, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { buildProviderRuntime } from './build.mjs';

await buildProviderRuntime();
const {createProviderService,ProviderFailure}=await import('./provider-service.mjs');
const providers=await createProviderService({directory:process.env.POCKET_PREVIEW_STATE_DIR||join(homedir(),'.local/state/pocket-agent/live-ui')});
const previewPort=Number(process.env.POCKET_PREVIEW_PORT||3000);
if(!Number.isInteger(previewPort)||previewPort<0||previewPort>65535)throw new Error('Invalid preview port');

const root = dirname(fileURLToPath(import.meta.url));
const clients = new Set();
const types = { '.html': 'text/html', '.css': 'text/css', '.mjs':'text/javascript', '.js': 'text/javascript', '.svg': 'image/svg+xml' };
const allowed = new Set(['index.html', 'style.css', 'app.js', 'settings.js', 'connection.js', 'voice.js', 'activity.js', 'mark.svg', 'drawer-swipe.js', 'chat-titles.js', 'short-title.mjs', 'chat-view.js', 'refresh-scheduler.js', 'model-picker.js']);
function json(res,status,data){res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(data));}
async function readJson(req){let size=0;const chunks=[];for await(const chunk of req){size+=chunk.length;if(size>262144)throw new ProviderFailure('Request exceeds 256 KB.',413);chunks.push(chunk);}try{const input=JSON.parse(Buffer.concat(chunks).toString('utf8'));if(!input||typeof input!=='object'||Array.isArray(input))throw Error();return input;}catch{throw new ProviderFailure('Send a valid JSON object.');}}
const server = http.createServer(async (req, res) => {
  const port=req.socket.localPort,hosts=new Set([`localhost:${port}`,`127.0.0.1:${port}`]);
  if(!hosts.has(req.headers.host)){json(res,403,{error:'Localhost access only.'});return;}
  res.setHeader('X-Content-Type-Options','nosniff');
  res.setHeader('Referrer-Policy','no-referrer');
  res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
  const pathname = new URL(req.url, 'http://localhost').pathname;
  if(pathname.startsWith('/api/')){
    if(req.headers['x-pocket-request']!=='1'||req.headers['sec-fetch-site']==='cross-site'||(req.headers.origin&&!hosts.has(req.headers.origin.replace(/^http:\/\//,'')))){json(res,403,{error:'Same-origin requests only.'});return;}
    try{
      if(pathname==='/api/provider/status'&&req.method==='GET'){json(res,200,providers.status());return;}
      if(req.method!=='POST')throw new ProviderFailure('Use POST for this action.',405);
      if(pathname==='/api/voice/transcribe'){
        const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;if(size>10*1024*1024)throw new ProviderFailure('Recording exceeds 10 MB.',413);chunks.push(chunk);}
        json(res,200,await providers.transcribe(Buffer.concat(chunks),req.headers['content-type']||'',req.headers['x-pocket-speech-engine']||'deepgram'));return;
      }
      const input=await readJson(req);
      let result;
      switch(pathname){
        case '/api/auth/codex/start':result=await providers.start();break;
        case '/api/auth/codex/cancel':result=providers.cancel();break;
        case '/api/auth/codex/import':result=await providers.importAccount(input.document);break;
        case '/api/provider/key':result=await providers.saveKey(input);break;
        case '/api/provider/disconnect':result=await providers.disconnect(input.provider);break;
        case '/api/provider/models':result={models:await providers.models(input)};break;
        case '/api/chat/title':result=await providers.title(input);break;
        case '/api/voice/key':result=await providers.saveVoiceKey(input.apiKey,input.engine);break;
        case '/api/voice/disconnect':result=await providers.disconnectVoice(input.engine);break;
        case '/api/voice/speak':{const audio=await providers.speak(input.text);res.writeHead(200,{'Content-Type':'audio/mpeg','Cache-Control':'no-store'});res.end(audio);return;}
        case '/api/chat':{
          const controller=new AbortController();res.on('close',()=>{if(!res.writableEnded)controller.abort();});
          result=await providers.chat(input,controller.signal);break;
        }
        default:throw new ProviderFailure('Unknown provider action.',404);
      }
      json(res,200,result);
    }catch(error){if(!res.destroyed)json(res,error instanceof ProviderFailure?error.status:502,{error:error instanceof ProviderFailure?error.message:'The provider request failed. Check the connection and try again.'});}
    return;
  }
  if(req.method!=='GET'&&req.method!=='HEAD'){res.writeHead(405);res.end();return;}
  if (pathname === '/events' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    res.write(': connected\n\n');
    clients.add(res);
    req.on('close', () => clients.delete(res));
    return;
  }
  const name = pathname === '/' ? 'index.html' : pathname.slice(1);
  if (!allowed.has(name)) { res.writeHead(404); res.end('Not found'); return; }
  try {
    const data = await readFile(join(root, name));
    res.writeHead(200, { 'Content-Type': `${types[extname(name)]}; charset=utf-8`, 'Cache-Control': 'no-store' });
    res.end(data);
  } catch { res.writeHead(500); res.end('Preview asset unavailable'); }
});
watch(root, (_event, name) => {
  if (allowed.has(name)) for (const client of clients) client.write('data: reload\n\n');
});
// Android edits also refresh the design surface during our live iteration.
let sourceTime = 0;
setInterval(async () => {
  try {
    const { mtimeMs } = await stat(join(root, '../app/src/main/java/dev/pocket/agent/MainActivity.java'));
    if (sourceTime && mtimeMs !== sourceTime) for (const client of clients) client.write('data: reload\n\n');
    sourceTime = mtimeMs;
  } catch { /* The standalone preview still works without the Android source. */ }
}, 1500).unref();
server.listen(previewPort, '127.0.0.1', () => console.log(`Pocket Agent preview: http://localhost:${server.address().port}`));
server.on('close',()=>providers.close());
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{providers.close();server.closeAllConnections();server.close(()=>process.exit(0));});
