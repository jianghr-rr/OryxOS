import sys,json,os,time,signal
mode=sys.argv[1] if len(sys.argv)>1 else 'normal'
log=sys.argv[2] if len(sys.argv)>2 else None
if mode=='stubborn': signal.signal(signal.SIGTERM,signal.SIG_IGN)
print('stderr '+os.environ.get('EXPLICIT_SECRET','none'),file=sys.stderr,flush=True)
for line in sys.stdin:
    req=json.loads(line)
    if log:
        with open(log,'a') as file:file.write(json.dumps({'method':req.get('method'),'name':req.get('params',{}).get('name'),'foreign':os.environ.get('ORYXOS_PARENT_CANARY'),'provider':os.environ.get('DEEPSEEK_API_KEY')})+'\n')
    if 'id' not in req:continue
    method=req['method'];params=req.get('params',{})
    if method=='initialize':
        if mode=='init-fail':sys.exit(2)
        result={'protocolVersion':params['protocolVersion'],'capabilities':{'tools':{}},'serverInfo':{'name':'controlled-fixture','version':'1.0.0'}}
    elif method=='tools/list':
        schema={'type':'object','properties':{'text':{'type':'string'}},'required':['text'],'additionalProperties':False}
        if mode=='remote-ref':schema={'$ref':(sys.argv[3] if len(sys.argv)>3 else 'http://127.0.0.1:9/forbidden')}
        result={'tools':[{'name':'echo' if not params.get('cursor') else 'other','description':'fixture echo','inputSchema':schema}]}
        if mode=='secret-name':result['tools'][0]['name']=os.environ['EXPLICIT_SECRET']
        if not params.get('cursor'):result['nextCursor']='page-2'
    elif method=='tools/call':
        if mode=='disconnect':sys.exit(3)
        if mode=='slow':time.sleep(30)
        result={'content':[{'type':'text','text':params['name']+':'+params['arguments']['text']}],'isError':mode=='is-error'}
    elif method=='ping':result={}
    else:
        print(json.dumps({'jsonrpc':'2.0','id':req['id'],'error':{'code':-32601,'message':'unsupported'}}),flush=True);continue
    print(json.dumps({'jsonrpc':'2.0','id':req['id'],'result':result}),flush=True)

if mode=='stubborn':
    while True:time.sleep(1)
