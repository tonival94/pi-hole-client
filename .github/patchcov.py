import subprocess, sys, re, fnmatch
base=sys.argv[1]
ign=["lib/**/*.freezed.dart","lib/**/*.g.dart","lib/data/services/api/generated/**","lib/ui/core/l10n/generated/**"]
def ignored(f): return any(fnmatch.fnmatch(f,p) for p in ign)
diff=subprocess.check_output(['git','diff','-U0',base,'HEAD','--','lib']).decode()
added={}; cur=None
for line in diff.split('\n'):
    if line.startswith('+++ b/'): cur=line[6:]; added.setdefault(cur,set())
    m=re.match(r'@@ -\S+ \+(\d+)(?:,(\d+))? @@',line)
    if m and cur:
        st=int(m.group(1)); n=int(m.group(2) or 1)
        added[cur].update(range(st,st+n))
cov={}; f=None
for line in open('coverage/lcov.info'):
    if line.startswith('SF:'): f=line[3:].strip(); cov.setdefault(f,{})
    elif line.startswith('DA:'):
        n,h=line[3:].split(',')[:2]; cov[f][int(n)]=int(h)
tot=hit=0
for fpath,lines in sorted(added.items()):
    if ignored(fpath) or fpath not in cov: 
        if not ignored(fpath): print('NO COVERAGE DATA', fpath)
        continue
    rel=[l for l in lines if l in cov[fpath]]
    h=sum(1 for l in rel if cov[fpath][l]>0)
    miss=[l for l in rel if cov[fpath][l]==0]
    tot+=len(rel); hit+=h
    print(f'{fpath}: {h}/{len(rel)} missing {miss[:40]}')
print(f'PATCH {hit}/{tot} = {100*hit/max(tot,1):.1f}%')
