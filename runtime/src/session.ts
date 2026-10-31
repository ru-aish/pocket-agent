import type {DesktopConnector} from './desktop-connector.js';
import { Agent } from '@earendil-works/pi-agent-core';
import { makeStream, modelFor, type ReasoningEffort } from './provider.js';

/** Finite but generous budgets for multi-step phone and puzzle workflows. */
export const MOBILE_SESSION_BUDGET={turnsPerGeneration:200,toolCallsPerGeneration:800,maxMessages:2048,maxSerializedChars:8_000_000} as const;

/** The deliberately narrow Pi9 coding-AgentSession interface implemented by Pi Agent. */
export class MobileSession {
  readonly agent: Agent;
  private steering:string[]=[];
  private turns=0;
  private calls=0;
  constructor(readonly owner:string, config:any, credentials:any, tools:any[], messages:any[]=[] ,systemPrompt='', selectedModel?:string, reasoning?:ReasoningEffort, supportedReasoning?:readonly ReasoningEffort[],readonly connector?:DesktopConnector) {
    const current=modelFor(config,credentials);
    const selected=selectedModel===`${current.provider}/${current.id}`
      ? current : selectedModel ? modelFor(config,credentials,selectedModel) : current;
    // An explicit worker model is a catalog selection even when its ID matches
    // the manually configured main model. Omitted models inherit main's source.
    this.agent=new Agent({streamFn:makeStream(config,credentials,owner,reasoning,supportedReasoning,
      selectedModel === undefined ? credentials.modelSource : 'catalog',connector),initialState:{
      model:selected,systemPrompt,tools,messages,
    },toolExecution:'sequential',sessionId:owner,
      beforeToolCall:()=>{if(++this.calls>MOBILE_SESSION_BUDGET.toolCallsPerGeneration)throw new Error(`Mobile tool-call budget reached (${MOBILE_SESSION_BUDGET.toolCallsPerGeneration} per generation); saved context was retained`);return undefined;},
      finishTurn:()=>{if(++this.turns>MOBILE_SESSION_BUDGET.turnsPerGeneration)throw new Error(`Mobile generation turn budget reached (${MOBILE_SESSION_BUDGET.turnsPerGeneration}); saved context was retained`);if(this.full())throw new Error('Mobile saved conversation context limit reached; saved context was retained');return undefined;},
    });
  }
  get messages():any[] {return this.agent.state.messages;}
  full():boolean {return this.messages.length>=MOBILE_SESSION_BUDGET.maxMessages||JSON.stringify(this.messages,(_key,value)=>value?.type==='image'?{type:'image'}:value).length>=MOBILE_SESSION_BUDGET.maxSerializedChars;}
  dropPriorImages():void {
    // Mutate shared message objects so the active Pi loop's shallow context copy
    // releases prior image data too; retain every text block/result and receipt.
    for(const message of this.messages)if(Array.isArray(message.content))message.content=message.content.map((content:any)=>content.type==='image'?{type:'text',text:'[Previous screenshot omitted; observe again for current state]'}:content);
  }
  prompt(text:string):Promise<void> {if(this.full())return Promise.reject(new Error('Conversation is at the mobile context limit; history was retained'));this.turns=0;this.calls=0;return this.agent.prompt(text);}
  continue():Promise<void> {this.turns=0;this.calls=0;return this.agent.continue();}
  subscribe(fn:(event:any)=>void):()=>void {return this.agent.subscribe(fn);}
  async steer(text:string):Promise<void> {
    this.steering.push(text);this.agent.steer({role:'user',content:[{type:'text',text}],timestamp:Date.now()});
  }
  getSteeringMessages():string[] {return this.steering;}
  clearQueue():void {this.steering=[];this.agent.clearAllQueues();}
  async abort():Promise<void> {this.agent.abort();await this.agent.waitForIdle();}
  dispose():void {this.dropPriorImages();this.clearQueue();}
}
