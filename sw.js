const C='szamlaapp-render-v6';
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(C).then(c=>c.addAll(['./','./index.html','./manifest.json'])))});
self.addEventListener('activate',e=>{e.waitUntil(Promise.all([caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))),self.clients.claim()]))});
async function transformHtml(r){
  let t=await r.text();
  const css='<style>.save{height:72px!important;min-height:72px!important;font-size:19px!important;font-weight:800!important;border-radius:14px!important}.confirmOverlay{display:none;position:fixed;inset:0;z-index:99999;background:#0007;align-items:center;justify-content:center;padding:20px}.confirmOverlay.on{display:flex}.confirmBox{width:min(88vw,360px);background:#fff;border-radius:24px;padding:28px 22px 22px;text-align:center;box-shadow:0 18px 50px #0004;transform:translateY(-8vh)}.confirmBox h3{margin:0 0 22px;font-size:32px}.confirmActions{display:grid;grid-template-columns:1fr 1fr;gap:12px}.confirmActions button{height:58px;border:0;border-radius:16px;font-size:20px;font-weight:800}.confirmNo{background:#eef1f4;color:#222}.confirmYes{background:#168f5b;color:#fff}</style>';
  if(!t.includes('.confirmOverlay{')) t=t.replace('</head>',css+'</head>');
  const modal='<div id="paidConfirm" class="confirmOverlay" onclick="if(event.target===this)closePaidConfirm()"><div class="confirmBox"><h3>Biztos?</h3><div class="confirmActions"><button type="button" class="confirmNo" onclick="closePaidConfirm()">Nem</button><button type="button" class="confirmYes" onclick="confirmPaid()">Igen</button></div></div></div>';
  if(!t.includes('id="paidConfirm"')) t=t.replace('<script>',modal+'<script>');
  t=t.replace(/function markPaid\(id\)\{let x=data\.find\(a=>a\.id===id\);if\(!x\)return;if\(confirm\([\s\S]*?\)\)\{x\.paid=true;x\.paidDate=today\(\);saveStore\(\);render\(\)\}\}/,'let pendingPaidId=null;function markPaid(id){pendingPaidId=id;$("paidConfirm").classList.add("on")}function closePaidConfirm(){pendingPaidId=null;$("paidConfirm").classList.remove("on")}function confirmPaid(){let x=data.find(a=>a.id===pendingPaidId);if(x){x.paid=true;x.paidDate=today();saveStore();render()}closePaidConfirm()}');

  const recurring=`function monthStart(s){return (s||today()).slice(0,7)+"-01"}
function dueForMonth(src,month){if(!src.due)return "";let day=Number(src.due.slice(8,10))||1,d=dateObj(month),last=new Date(d.getFullYear(),d.getMonth()+1,0).getDate();d.setDate(Math.min(day,last));return d.toISOString().slice(0,10)}
function isTransferTemplate(src){return !!(src.regular&&!src.seriesId&&!src.number)}
function transferAnchor(src){return monthStart(src.due||src.arrival||today())}
function nextTransferMonth(src){let step=src.frequency==="quarterly"?3:1,anchor=transferAnchor(src),children=data.filter(x=>x.seriesId===src.id&&x.occurrenceMonth).sort((a,b)=>a.occurrenceMonth.localeCompare(b.occurrenceMonth));if(children.length)return addMonths(children[children.length-1].occurrenceMonth,step);return src.templateOnly?anchor:addMonths(anchor,step)}
function ensureRecurringTransfers(){let changed=false,currentMonth=monthStart(today());data.filter(isTransferTemplate).forEach(src=>{let step=src.frequency==="quarterly"?3:1,m=src.templateOnly?transferAnchor(src):addMonths(transferAnchor(src),step),guard=0;while(m<=currentMonth&&guard++<120){let exists=data.some(x=>x.seriesId===src.id&&((x.occurrenceMonth||monthStart(x.arrival||"9999-12-01"))===m));if(!exists){data.push({id:Date.now()+guard,partner:src.partner,number:"",amount:src.amount??null,currency:src.currency||"HUF",due:dueForMonth(src,m),arrival:m,regular:false,frequency:null,seriesId:src.id,occurrenceMonth:m,templateOnly:false,paid:false,paidDate:null});changed=true}m=addMonths(m,step)}});if(changed)saveStore()}
function nextExpected(src){if(isTransferTemplate(src))return nextTransferMonth(src);let step=src.frequency==="quarterly"?3:1,d=addMonths(src.arrival,step),sameSeries=data.filter(x=>x.seriesId===(src.seriesId||src.id)&&x.id!==src.id).sort((a,b)=>(a.arrival||"").localeCompare(b.arrival||""));if(sameSeries.length)d=addMonths(sameSeries[sameSeries.length-1].arrival,step);return d}
function expectedItems(){let regs=data.filter(x=>x.regular&&!x.seriesId&&!isTransferTemplate(x)),out=[];regs.forEach(src=>{let e=nextExpected(src);while(e<=today()||diff(e)<=3){let sid=src.id,exists=data.some(x=>x.seriesId===sid&&(x.arrival||"").slice(0,7)===e.slice(0,7));if(!exists&&diff(e)<=3)out.push({src,expected:e});if(e>today())break;e=addMonths(e,src.frequency==="quarterly"?3:1);if(out.length>100)break}});return out.sort((a,b)=>a.expected.localeCompare(b.expected))}
function render(){ensureRecurringTransfers();`;
  t=t.replace(/function nextExpected\(src\)\{[\s\S]*?function render\(\)\{/,recurring);
  t=t.replace('let templateOnly=regular && !obj.number && obj.amount===null && !obj.due;','let templateOnly=regular && !obj.number;');
  t=t.replace('Rendszeres számla?','Rendszeres?');
  t=t.replace('Rendszeres tételnél elég a partner neve és a gyakoriság. A többi adat később is megadható.','Rendszeres utalás minden esedékes hónap 1-jén megjelenik. A rögzített fizetési határidőt viszi tovább.');

  // A havi/negyedéves sablonból létrejött aktuális tétel javításakor az összeg mindig szerkeszthető.
  // A módosítás csak az adott havi tételt érinti; a rendszeres sablon összege változatlan marad.
  t=t.replace(/function editInvoice\(id\)\{let x=data\.find\(a=>a\.id===id\);editId=id;expectedSourceId=null;[\s\S]*?\$\("modal"\)\.classList\.add\("on"\)\}/,
`function editInvoice(id){let x=data.find(a=>a.id===id);editId=id;expectedSourceId=null;let occurrence=!!x.seriesId;$("formTitle").textContent=occurrence?"Aktuális tétel javítása":(x.templateOnly?"Rendszeres tétel javítása":"Számla javítása");$("partner").disabled=false;$("partner").value=x.partner;$("number").value=x.number||"";$("amount").value=x.amount??"";$("currency").value=x.currency||"HUF";$("due").value=x.due||"";$("isRegular").checked=occurrence?false:!!x.regular;$("isRegular").disabled=occurrence;$("frequency").value=x.frequency||"monthly";if($("regNoAmount")){if(x.amount===null||x.amount===undefined||x.amount==="")$("regNoAmount").checked=true;else $("regWithAmount").checked=true;}updateRequiredFields();if(occurrence){$("amount").disabled=false;$("amount").required=true;$("freqWrap").style.display="none";$("regularHint").style.display="none";}$("autoDate").textContent=(x.templateOnly?"Rögzítve: ":"Beérkezés: ")+hd(x.arrival);$("modal").classList.add("on")}`);

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