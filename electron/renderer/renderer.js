const fileInput = document.getElementById('fileInput');
const choose = document.getElementById('choose');
const cameraToggle = document.getElementById('cameraToggle');
const video = document.getElementById('camera');
const canvas = document.getElementById('contour');
const previewImg = document.getElementById('previewImg');
const placeholder = document.getElementById('placeholder');
const statusEl = document.getElementById('status');
const countdownEl = document.getElementById('countdown');
const providerInfo = document.getElementById('providerInfo');
const stageCapture = document.getElementById('stageCapture');
const stagePreview = document.getElementById('stagePreview');
const stageSolving = document.getElementById('stageSolving');
const stageAnswer = document.getElementById('stageAnswer');
const stageError = document.getElementById('stageError');
const reasoningEl = document.getElementById('reasoning');
const solvingSub = document.getElementById('solvingSub');
const progressDot = document.getElementById('progressDot');
const answers = document.getElementById('answers');
const errorMsg = document.getElementById('errorMsg');
let stream = null;
let countdownTimer = null;

function setStatus(t){ statusEl.textContent = String(t||'READY').toUpperCase(); }
function showStage(name){
  stageCapture.hidden = name!=='capture';
  stagePreview.hidden = name!=='preview';
  stageSolving.hidden = name!=='solving';
  stageAnswer.hidden = name!=='answer';
  stageError.hidden = name!=='error';
}
function setCountdown(n){
  countdownEl.textContent = n==null ? '--' : String(n);
  providerInfo.textContent = n==null ? 'READY · SELECT IMAGE' : `CAPTURE IN ${n}s · gemini-3.7-flash`;
}
function showPlaceholder(show){
  placeholder.hidden = !show;
  previewImg.hidden = show;
  canvas.hidden = true;
  video.hidden = true;
}
function showImage(dataUrl){
  // 修复预览没内容：用 img 元素直接显示，canvas 仅用于滤镜预览（可选）
  showStage('preview');
  placeholder.hidden = true;
  video.hidden = true;
  canvas.hidden = true;
  previewImg.hidden = false;
  previewImg.src = dataUrl;
  // 同时在 canvas 绘制滤镜版本（低亮度）供后续需要
  const img = new Image();
  img.onload = () => {
    canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
    const ctx = canvas.getContext('2d');
    ctx.filter = 'brightness(.38) grayscale(1) contrast(1.5)';
    ctx.drawImage(img,0,0);
  };
  img.src = dataUrl;
}
function renderAnswer(result){
  answers.replaceChildren();
  const blocks = Array.isArray(result) ? result : (result?.answerBlocks||result?.blocks||[{type:'text',content: result?.answer||result?.text||String(result||'')}]);
  blocks.forEach(b=>{
    const el=document.createElement('div');
    el.className=`answer ${b.type||'text'}`;
    el.textContent=b.content??b.text??b.formula??'';
    answers.appendChild(el);
  });
}
function renderError(msg){
  errorMsg.textContent = String(msg||'ERROR');
  showStage('error');
  setStatus('ERROR');
}
async function solve(dataUrl){
  showStage('solving');
  setStatus('SOLVING');
  solvingSub.textContent='理解问题…';
  reasoningEl.textContent='';
  progressDot.style.left='0%';
  let progress=0;
  const progTimer=setInterval(()=>{ progress=Math.min(92,progress+7); progressDot.style.left=progress+'%'; },300);
  try{
    const r = await window.api.solve(dataUrl);
    clearInterval(progTimer); progressDot.style.left='100%';
    if(r?.error) throw new Error(r.error);
    const answer = r?.answer || r;
    renderAnswer(answer);
    showStage('answer');
    setStatus('DONE');
    solvingSub.textContent='';
  }catch(e){
    clearInterval(progTimer);
    renderError(e.message||'解题失败');
  }
}
function readFile(file){
  if(!file) return;
  clearInterval(countdownTimer);
  const r=new FileReader();
  r.onload=()=>{ showImage(r.result); solve(r.result); };
  r.readAsDataURL(file);
}
function startCaptureCountdown(){
  showStage('capture');
  showPlaceholder(true);
  setStatus('AWAIT');
  let n=3;
  setCountdown(n);
  clearInterval(countdownTimer);
  countdownTimer=setInterval(()=>{
    n-=1;
    if(n<=0){ clearInterval(countdownTimer); setCountdown('--'); providerInfo.textContent='AWAIT IMAGE · IMAGE / CAMERA'; }
    else setCountdown(n);
  },1000);
}
// 初始化：显示 capture 倒计时与 provider 信息
startCaptureCountdown();
choose.onclick=()=> fileInput.click();
fileInput.onchange=()=> fileInput.files[0] && readFile(fileInput.files[0]);

cameraToggle.onclick=async()=>{
  if(stream){
    stream.getTracks().forEach(t=>t.stop()); stream=null;
    video.srcObject=null;
    showPlaceholder(true);
    showStage('preview');
    cameraToggle.textContent='CAMERA';
    return;
  }
  try{
    stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:'environment'},audio:false});
    showStage('preview');
    placeholder.hidden=true;
    previewImg.hidden=true;
    canvas.hidden=true;
    video.hidden=false;
    video.srcObject=stream;
    cameraToggle.textContent='STOP';
    // 点击拍照
    video.onclick=()=>{
      if(!stream) return;
      canvas.width=video.videoWidth; canvas.height=video.videoHeight;
      const ctx=canvas.getContext('2d');
      ctx.drawImage(video,0,0);
      const dataUrl=canvas.toDataURL('image/jpeg',0.9);
      // 停止预览流，保持图片
      stream.getTracks().forEach(t=>t.stop()); stream=null; video.srcObject=null;
      video.hidden=true; canvas.hidden=true;
      showImage(dataUrl);
      solve(dataUrl);
      cameraToggle.textContent='CAMERA';
    };
  }catch{ setStatus('CAMERA UNAVAILABLE'); renderError('相机不可用'); }
};

window.api.onProgress((p)=>{
  const stage=typeof p==='string'?p:p?.stage||p?.message||'';
  const msg=p?.message||p?.data||'';
  if(stage==='stage1' || stage==='stage2'){
    solvingSub.textContent=String(msg||stage).slice(0,48);
    reasoningEl.textContent+= (msg? msg+'\n':'');
    reasoningEl.scrollTop=reasoningEl.scrollHeight;
  }
  setStatus(typeof p==='string'?p:p?.stage||'WORKING');
});
