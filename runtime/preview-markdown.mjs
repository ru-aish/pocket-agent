import MarkdownIt from 'markdown-it';

// All assistant text is untrusted. Markdown-it escapes HTML when html:false,
// validates link protocols, and supports tables, lists, fences and emphasis.
const renderer = new MarkdownIt({html:false,linkify:true,breaks:false,typographer:false});
// Do not load remote images supplied by model output inside the Android WebView.
renderer.renderer.rules.image = (tokens,index) => `[Image: ${renderer.utils.escapeHtml(tokens[index].content)}]`;
renderer.renderer.rules.link_open = (tokens,index,options,env,self) => {
  tokens[index].attrSet('target','_blank');
  tokens[index].attrSet('rel','noopener noreferrer');
  return self.renderToken(tokens,index,options);
};

export function renderMarkdown(value) {
  return renderer.render(String(value ?? ''));
}
