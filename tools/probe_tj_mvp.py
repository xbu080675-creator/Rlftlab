import json, re, urllib.parse, urllib.request, urllib.error

UA='Mozilla/5.0 RiftLab-MVP-Probe/1.0'
AUTH='7935be4c41d8760a28c05581a7b1f570'


def req(url, auth=False):
    headers={'User-Agent':UA,'Accept':'application/json,text/plain,*/*','Referer':'https://lpl.qq.com/'}
    if auth:
        headers['authorization']=AUTH
        headers['Authorization']=AUTH
    r=urllib.request.Request(url,headers=headers)
    try:
        with urllib.request.urlopen(r,timeout=20) as f:
            raw=f.read().decode('utf-8','replace')
            return f.status,raw
    except urllib.error.HTTPError as e:
        return e.code,e.read().decode('utf-8','replace')
    except Exception as e:
        return 0,repr(e)


def loose(text):
    text=text.lstrip('\ufeff').strip()
    # JSONP / JS assignment wrapper
    a=text.find('{'); b=text.rfind('}')
    if a>=0 and b>a:
        text=text[a:b+1]
    return json.loads(text)

print('=== SWAGGER DISCOVERY ===')
spec=None
for u in [
 'https://open.tjstats.com/match-auth-app/swagger/v1/doc.json',
 'https://open.tjstats.com/match-auth-app/swagger/v1/swagger.json',
 'https://open.tjstats.com/match-auth-app/swagger/doc.json',
 'https://open.tjstats.com/match-auth-app/v2/api-docs',
]:
    st,body=req(u)
    print('swagger',st,u,'head=',body[:120].replace('\n',' '))
    if st==200:
        try:
            j=json.loads(body)
            if isinstance(j,dict) and 'paths' in j:
                spec=j; print('swagger paths',len(j['paths'])); break
        except Exception as e: print('swagger json err',e)

if spec:
    for path in ['/compound/mvp','/compound/playerMvpVotesRank','/compound/matchDetail']:
        op=(spec.get('paths',{}).get(path,{}) or {}).get('get',{}) or {}
        print('\nPATH',path,'summary=',op.get('summary'))
        for p in op.get('parameters',[]) or []:
            print(' param',p.get('name'),'in=',p.get('in'),'required=',p.get('required'),'type=',p.get('type'),'schema=',p.get('schema'),'desc=',p.get('description'))
        print(' responses=',list((op.get('responses') or {}).keys()))

print('\n=== RESOLVE LGD vs IG 2026-09-08 ===')
st,body=req('https://lpl.qq.com/web201612/data/LOL_MATCH2_GAME_LIST_BRIEF.js')
print('game list',st,body[:80].replace('\n',' '))
root=loose(body)
s=root.get('msg',{}).get('sGameList',{})
gids=[]
for arr in s.values():
    if isinstance(arr,list):
        for x in arr:
            if isinstance(x,dict) and x.get('GameId') is not None:
                gids.append(str(x.get('GameId')))
gids=sorted(set(gids),key=lambda x:int(x) if x.isdigit() else -1,reverse=True)
print('game ids top',gids[:20])

found=[]
for gid in gids[:24]:
    url=f'https://lpl.qq.com/web201612/data/LOL_MATCH2_MATCH_HOMEPAGE_BMATCH_LIST_{urllib.parse.quote(gid)}.js'
    st,body=req(url)
    if st!=200: continue
    try:j=loose(body)
    except:continue
    arr=j.get('msg',[])
    if not isinstance(arr,list): continue
    for o in arr:
        if not isinstance(o,dict):continue
        name=str(o.get('bMatchName') or o.get('BMatchName') or o.get('matchName') or '')
        date=str(o.get('MatchDate') or o.get('matchDate') or o.get('startTime') or '')
        if '2026-09-08' in date and 'LGD' in name.upper() and 'IG' in name.upper():
            found.append((gid,o))

print('found count',len(found))
for gid,o in found:
    print('FOUND gid',gid,'obj=',json.dumps(o,ensure_ascii=False)[:1200])

if not found:
    raise SystemExit('target bmatch not found')
o=found[0][1]
bmid=str(o.get('bMatchId') or o.get('bMatchID') or o.get('BMatchId') or '')
print('TARGET_BMID',bmid)

base='https://open.tjstats.com/match-auth-app/open/v1'
st,md=req(base+'/compound/matchDetail?'+urllib.parse.urlencode({'matchId':bmid}),auth=True)
print('matchDetail',st,'head=',md[:300].replace('\n',' '))
ctx={}
if st==200:
    try:
        jr=json.loads(md); data=jr.get('data',jr)
        print('matchDetail top keys',sorted(data.keys()) if isinstance(data,dict) else type(data))
        if isinstance(data,dict):
            for k,v in data.items():
                lk=k.lower()
                if any(t in lk for t in ['match','game','season','stage','mvp','team','score']):
                    if isinstance(v,(str,int,float,bool)) or v is None:
                        print(' MD_FIELD',k,'=',v)
                    elif isinstance(v,list):
                        print(' MD_FIELD',k,'= LIST len',len(v),'sample',json.dumps(v[:1],ensure_ascii=False)[:700])
                    elif isinstance(v,dict):
                        print(' MD_FIELD',k,'= OBJ',json.dumps(v,ensure_ascii=False)[:700])
            ctx=data
    except Exception as e: print('matchDetail parse err',e)

print('\n=== DIRECT MVP PROBES ===')
# start with common IDs from target and matchDetail
vals={'matchId':bmid,'bMatchId':bmid,'bmid':bmid}
if isinstance(ctx,dict):
    for k,v in ctx.items():
        if isinstance(v,(str,int)) and v not in ('',0):
            vals[k]=str(v)

# Print exact swagger-informed probes if possible.
paths=['/compound/mvp','/compound/playerMvpVotesRank']
for path in paths:
    print('\nPROBE PATH',path)
    param_sets=[]
    if spec:
        op=(spec.get('paths',{}).get(path,{}) or {}).get('get',{}) or {}
        params=op.get('parameters',[]) or []
        required=[p.get('name') for p in params if p.get('required')]
        allnames=[p.get('name') for p in params]
        print(' swagger required=',required,'all=',allnames)
        q={}
        for n in allnames:
            if not n: continue
            # exact / case-insensitive / common alias lookup
            cand=next((v for k,v in vals.items() if k.lower()==n.lower()),None)
            if cand is None:
                alias={'bmatchid':bmid,'matchid':bmid}.get(n.lower())
                cand=alias
            if cand is not None:q[n]=cand
        if q:param_sets.append(q)
    # fallback candidates for diagnostics
    param_sets += [
        {'matchId':bmid}, {'bMatchId':bmid}, {'bmid':bmid},
    ]
    seen=set()
    for q in param_sets:
        qs=urllib.parse.urlencode(q)
        if qs in seen:continue
        seen.add(qs)
        st,body=req(base+path+'?'+qs,auth=True)
        print(' ',st,qs,'=>',body[:900].replace('\n',' '))

print('\n=== DONE ===')
