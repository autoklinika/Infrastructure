'use strict';
const survey = JSON.parse(document.getElementById('survey-data').textContent);
const map = L.map('map', {zoomControl:true, attributionControl:true, preferCanvas:true, maxZoom:21});
map.attributionControl.setPrefix(false);
const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxNativeZoom:19,maxZoom:21,updateWhenIdle:true,keepBuffer:1,
  attribution:'© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
}).addTo(map);
const failedTiles=new Set();
function tileStatus(event,failed){const key=event.coords.x+','+event.coords.y+','+event.coords.z;if(failed)failedTiles.add(key);else failedTiles.delete(key);document.getElementById('tile-status').hidden=failedTiles.size===0}
tiles.on('tileerror',event=>tileStatus(event,true));
tiles.on('tileload',event=>tileStatus(event,false));
tiles.on('tileunload',event=>tileStatus(event,false));
L.control.scale({imperial:false}).addTo(map);
const reset=L.control({position:'topright'});
reset.onAdd=()=>{const button=L.DomUtil.create('button');button.textContent='Cały pomiar';button.onclick=()=>draw(true);L.DomEvent.disableClickPropagation(button);return button};reset.addTo(map);
const layer=L.layerGroup().addTo(map), focusLayer=L.layerGroup().addTo(map);
const qualityLayer=L.layerGroup().addTo(map);
let qualityLabels=[];
let focusPosition=null;
const apById = new Map(survey.aps.map(ap=>[ap.id,ap]));
const networks=[...new Set(survey.aps.map(ap=>ap.network))].sort();
const select=document.getElementById('network');
const allNetworks=document.createElement('option');allNetworks.textContent='Wszystkie sieci';allNetworks.value='';select.append(allNetworks);
networks.forEach(name=>{const option=document.createElement('option');option.value=name;option.textContent=name;select.append(option)});
select.value=networks.includes(survey.defaultNetwork)?survey.defaultNetwork:(networks[0]||'');
let source='SCAN_RESULT', selectedAp=null;
function color(level){return level>=-67?'#096b60':level>=-75?'#c18408':'#b0393c'}
function signalBadge(title,level,emptyLabel='Brak odczytu'){
  const badge=document.createElement('div');badge.className='signal-badge';
  badge.style.borderColor=level===null?'#526865':color(level);
  const name=document.createElement('span');name.className='signal-name';name.textContent=title;badge.append(name);
  const value=document.createElement('div');value.className='signal-value';
  const bars=document.createElement('span');bars.className='signal-bars';bars.setAttribute('aria-hidden','true');
  const strength=level===null?0:level>=-55?4:level>=-67?3:level>=-75?2:1;
  for(let i=0;i<4;i++){const bar=document.createElement('span');bar.style.height=(5+i*3)+'px';bar.style.background=i<strength?color(level):'#dce7e2';bars.append(bar)}
  const label=document.createElement('strong');label.textContent=level===null?emptyLabel:String(level).replace('-','−')+' dBm';
  value.append(bars,label);badge.append(value);return badge;
}
function redrawQualityLabels(){
  qualityLayer.clearLayers();const occupied=[],size=map.getSize();
  if(focusPosition){const p=map.latLngToContainerPoint(focusPosition);occupied.push({left:p.x-76,right:p.x+76,top:p.y-74,bottom:p.y+16})}
  let shown=0;
  for(const item of qualityLabels){
    if(!map.getBounds().contains(item.position))continue;
    const p=map.latLngToContainerPoint(item.position);
    let anchor=null,box=null;
    for(const offset of [0,76,-76]){
      const candidate={left:p.x-54,right:p.x+54,top:p.y+offset-24,bottom:p.y+offset+24};
      if(candidate.left<6||candidate.top<6||candidate.right>size.x-6||candidate.bottom>size.y-6)continue;
      if(occupied.some(other=>candidate.left<other.right+6&&candidate.right>other.left-6&&candidate.top<other.bottom+6&&candidate.bottom>other.top-6))continue;
      box=candidate;anchor=map.containerPointToLatLng([p.x,p.y+offset]);break;
    }
    if(!anchor)continue;
    occupied.push(box);
    if(!anchor.equals(L.latLng(item.position)))L.polyline([item.position,anchor],{color:'#526865',weight:1,opacity:.6,interactive:false}).addTo(qualityLayer);
    L.marker(anchor,{interactive:false,icon:L.divIcon({className:'quality-marker',
      html:signalBadge(item.label,item.level),iconSize:[108,48],iconAnchor:[54,24]})}).addTo(qualityLayer);
    if(++shown>=80)break;
  }
}
map.on('zoomend moveend resize',redrawQualityLabels);
function chip(parent,label,selected,action){const b=document.createElement('button');b.textContent=label;b.setAttribute('aria-pressed',String(selected));b.onclick=action;parent.append(b)}
function inNetwork(ap){return select.selectedIndex===0||ap.network===select.value}
function networkSources(){return [...new Set(survey.aps.filter(inNetwork).map(ap=>ap.source))]}
function chooseNetwork(){const sources=networkSources();source=sources.includes('SCAN_RESULT')?'SCAN_RESULT':'CONNECTED_LINK';selectedAp=null;draw(true)}
function matching(ap){return ap&&inNetwork(ap)&&ap.source===source}
function popup(cell,signals){
  const root=document.createElement('div'), title=document.createElement('strong');title.textContent='Wyniki w tym polu';root.append(title);
  signals.slice().sort((a,b)=>b.sustained-a.sustained).forEach(signal=>{
    const ap=apById.get(signal.ap), line=document.createElement('p');line.style.margin='7px 0';
    line.textContent=ap.label+': co najmniej '+signal.sustained+' dBm w 90% odczytów. Mediana '+signal.median+' dBm · '+signal.count+' odczytów. Zakres '+signal.low+'…'+signal.high+' dBm. Dokładność GPS do '+Math.ceil(signal.accuracy)+' m.';root.append(line);
    const identity=document.createElement('small');identity.textContent=ap.network+' · '+ap.id.split(':').slice(1).join(':');root.append(identity);
  });
  const note=document.createElement('p');note.textContent='Puste miejsce = brak miarodajnego pomiaru. Widoczność AP nie potwierdza dostępu do internetu.';root.append(note);return root;
}
function draw(fit){
  layer.clearLayers();qualityLayer.clearLayers();qualityLabels=[];const aps=survey.aps.filter(matching);
  const sources=document.getElementById('sources');sources.replaceChildren();
  if(networkSources().length>1){
    chip(sources,'Wykryte AP',source==='SCAN_RESULT',()=>{source='SCAN_RESULT';selectedAp=null;draw(true)});
    chip(sources,'Połączenie telefonu',source==='CONNECTED_LINK',()=>{source='CONNECTED_LINK';selectedAp=null;draw(true)});
  }
  const apControls=document.getElementById('aps');apControls.replaceChildren();
  chip(apControls,'Wszystkie AP',selectedAp===null,()=>{selectedAp=null;draw(false)});
  aps.forEach(ap=>chip(apControls,ap.label,selectedAp===ap.id,()=>{selectedAp=ap.id;draw(false)}));
  const bounds=[];let fields=0, tentative=0;
  survey.cells.forEach(cell=>{
    const all=cell.signals.filter(signal=>matching(apById.get(signal.ap)));
    if(!all.length)return;
    bounds.push([cell.south,cell.west],[cell.north,cell.east]);
    const signals=all.filter(signal=>selectedAp===null||signal.ap===selectedAp);
    if(!signals.length)return;
    // Prefer repeated evidence over an isolated strong observation in the combined view.
    const supported=signals.filter(signal=>signal.count>=3);
    const best=(supported.length?supported:signals).reduce((a,b)=>a.sustained>=b.sustained?a:b);
    const center=[(cell.south+cell.north)/2,(cell.west+cell.east)/2];
    const enough=best.count>=3; if(enough)fields++;else tentative++;
    const shape=enough?L.rectangle([[cell.south,cell.west],[cell.north,cell.east]],{
      color:color(best.sustained),weight:1.5,fillOpacity:.42
    }):L.circleMarker(center,{radius:6,color:color(best.sustained),fillOpacity:0,weight:2});
    shape.addTo(layer).bindPopup(popup(cell,signals));
    if(enough)qualityLabels.push({position:center,label:apById.get(best.ap).label,level:best.sustained,count:best.count});
  });
  const which=selectedAp?apById.get(selectedAp).label:'Najlepszy sygnał z AP';
  document.getElementById('caption').textContent=which+' · zbadane pola: '+fields+(tentative?' · wstępne: '+tentative:'');
  document.getElementById('map-tip').textContent=(source==='CONNECTED_LINK'?'Zapis połączenia telefonu. ':'')+'Dotknij pola, aby zobaczyć wynik. Puste miejsce: brak pomiarów.';
  document.getElementById('hint').textContent=(source==='CONNECTED_LINK'?'Zapis połączonego AP. ':'Skan widocznych AP. ')+
    'Kolor: sygnał osiągnięty w ≥90% odczytów. Dobry: ≥ −67 dBm. Słabszy: od −75 do −67 dBm. Słaby: < −75 dBm. '+
    'Pola '+survey.cellMeters+' × '+survey.cellMeters+' m, minimum 3 odczyty. Kółko: za mało danych. Puste: nie zbadano. GPS w budynku nie zastępuje planu pomieszczeń.';
  if(fit&&bounds.length)map.fitBounds(bounds,{padding:[25,25],maxZoom:19,animate:false});
  else if(!bounds.length){map.setView([0,0],2);document.getElementById('hint').textContent='Brak dokładnych pomiarów GPS dla tej sieci.'}
  qualityLabels.sort((a,b)=>b.count-a.count);redrawQualityLabels();
}
select.onchange=chooseNetwork;
window.setSurveyFocus=function(point){
  focusLayer.clearLayers();focusPosition=point?[point.latitude,point.longitude]:null;
  if(!point){redrawQualityLabels();return}
  const position=[point.latitude,point.longitude];
  L.circle(position,{radius:point.accuracy,color:'#183b3c',weight:1,fillOpacity:.06}).addTo(focusLayer);
  const level=point.connected?point.rssi:null;
  L.circleMarker(position,{radius:7,color:'#fff',fillColor:level===null?'#526865':color(level),fillOpacity:1,weight:3})
    .addTo(focusLayer).bindTooltip(signalBadge('Telefon · '+point.time,level,point.connected?'Brak odczytu':'Brak połączenia'),
      {permanent:true,direction:'top',offset:[0,-10],className:'signal-tooltip',opacity:1});
  if(point.follow){const size=map.getSize();map.panInside(position,{padding:[Math.min(72,size.x/4),Math.min(72,size.y/4)],animate:false})}
  redrawQualityLabels();
};
chooseNetwork();new ResizeObserver(()=>map.invalidateSize({pan:false})).observe(document.getElementById('map'));
