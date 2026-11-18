import os, sys, time, json
mode=sys.argv[1] if len(sys.argv)>1 else 'normal'
if mode=='sleep':
    print('started',flush=True)
    time.sleep(30)
elif mode=='large':
    print('x'*100000)
    print('fixture-explicit-secret',file=sys.stderr)
else:
    print(json.dumps({'args':sys.argv[1:], 'foreign':os.environ.get('ORYXOS_PARENT_CANARY'), 'provider':os.environ.get('DEEPSEEK_API_KEY'), 'explicit':os.environ.get('ALLOWED_TEST_VALUE')}))
