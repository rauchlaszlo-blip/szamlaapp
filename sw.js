const C='szamlaapp-render-v4';
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(C).then(c=>c.addAll(['./','./index.html','./manifest.json'])))});
self.addEventListener('activate',e=>{e.waitUntil(Promise.all([caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))),self.clients.claim()]))});
async function transformHtml(r){
  let t=await r.text();
  const css='<style>.save{height:72px!important;min-height:72px!important;font-size:19px!important;font-weight:800!important;border-radius:14px!important}.confirmOverlay{display:none;position:fixed;inset:0;z-index:99999;background:#0007;align-items:center;justify-content:center;padding:20px}.confirmOverlay.on{display:flex}.confirmBox{width:min(88vw,360px);background:#fff;border-radius:24px;padding:28px 22px 22px;text-align:center;box-shadow:0 18px 50px #0004;transform:translateY(-8vh)}.confirmBox h3{margin:0 0 22px;font-size:32px}.confirmActions{display:grid;grid-template-columns:1fr 1fr;gap:12px}.confirmActions button{height:58px;border:0;border-radius:16px;font-size:20px;font-weight:800}.confirmNo{background:#eef1f4;color:#222}.confirmYes{background:#168f5b;color:#fff}</style>';
  if(!t.includes('.confirmOverlay{')) t=t.replace('</head>',css+'</head>');
  const modal='<div id="paidConfirm" class="confirmOverlay" onclick="if(event.target===this)closePaidConfirm()"><div class="confirmBox"><h3>Biztos?</h3><div class="confirmActions"><button type="button" class="confirmNo" onclick="closePaidConfirm()">Nem</button><button type="button" class="confirmYes" onclick="confirmPaid()">Igen</button></div></div></div>';
  if(!t.includes('id="paidConfirm"')) t=t.replace('<script>',modal+'<script>');
  t=t.replace(/function markPaid\(id\)\{let x=data\.find\(a=>a\.id===id\);if\(!x\)return;if\(confirm\([\s\S]*?\)\)\{x\.paid=true;x\.paidDate=today\(\);saveStore\(\);render\(\)\}\}/,'let pendingPaidId=null;function markPaid(id){pendingPaidId=id;$("paidConfirm").classList.add("on")}function closePaidConfirm(){pendingPaidId=null;$("paidConfirm").classList.remove("on")}function confirmPaid(){let x=data.find(a=>a.id===pendingPaidId);if(x){x.paid=true;x.paidDate=today();saveStore();render()}closePaidConfirm()}');
  const h=new Headers(r.headers);h.set('content-type','text/html; charset=utf-8');
  return new Response(t,{status:r.status,statusText:r.statusText,headers:h});
}
self.addEventListener('fetch',e=>{
  const u=new URL(e.request.url);
  if(e.request.mode==='navigate'||u.pathname.endsWith('/index.html')||u.pathname==='/'){
    e.respondWith(fetch(e.request).then(transformHtml).catch(async()=>{const r=await caches.match('./index.html');return r?transformHtml(r):Response.error()}));return;
  }
  e.respondWith(fetch(e.request).catch(()=>caches.match(e.request)));
});