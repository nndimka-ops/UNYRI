"""Generate an absent operator-local file; never print secret values.

No network/container actions. It does not provision S3 users or install a server.
The output belongs outside the checkout, in a private0700 operator directory.
"""
import argparse,ipaddress,json,os,re,secrets,stat
from pathlib import Path

def hostname(value):
 if len(value)>253 or'.'not in value or not all(re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?',label)for label in value.split('.')):
  raise argparse.ArgumentTypeError('Use a DNS hostname without scheme, port or path')
 return value.lower()
def public_ipv4(value):
 try:address=ipaddress.IPv4Address(value)
 except ValueError:raise argparse.ArgumentTypeError('Use the reachable public IPv4')
 if not address.is_global:raise argparse.ArgumentTypeError('Use a global public IPv4; CGNAT/private addresses do not provide public TURN')
 return value
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--app-domain',type=hostname,required=True)
parser.add_argument('--files-domain',type=hostname,required=True)
parser.add_argument('--turn-host',type=hostname,required=True)
parser.add_argument('--turn-public-ipv4',type=public_ipv4,required=True)
parser.add_argument('--tls-email',required=True)
parser.add_argument('--caddyfile',type=Path,required=True)
for component in ['postgres','redis','minio','caddy','coturn']:
 parser.add_argument('--'+component+'-image',required=True,help='Reviewed upstream image pinned with @sha256:<manifest-digest>')
args=parser.parse_args()
assert os.name=='posix'and os.geteuid()==0,'Run on the intended Linux server as its operator'
assert args.app_domain!=args.files_domain,'Use distinct application and S3 hostnames'
assert re.fullmatch(r'[A-Za-z0-9._+-]+@[A-Za-z0-9.-]+',args.tls_email),'Use a simple contact email without dotenv interpolation/comment characters'
output=args.output.absolute();parent=output.parent
assert parent.is_dir()and not parent.is_symlink()and parent.stat().st_uid==0 and stat.S_IMODE(parent.stat().st_mode)==0o700,'Create a root-owned0700 configuration directory outside Git first'
caddyfile=args.caddyfile.absolute();assert caddyfile.is_file()and not caddyfile.is_symlink()
for component in ['postgres','redis','minio','caddy','coturn']:
 assert re.fullmatch(r'[a-z0-9][a-z0-9./_-]*@sha256:[0-9a-f]{64}',getattr(args,component+'_image')),'Use a reviewed public repository manifest digest, not a local Docker imageID'
values={'APP_DOMAIN':args.app_domain,'FILES_DOMAIN':args.files_domain,'TURN_HOST':args.turn_host,'TURN_EXTERNAL_IP':args.turn_public_ipv4,'TLS_EMAIL':args.tls_email,'UNYRI_CADDYFILE':str(caddyfile)}
assert re.fullmatch(r'/[A-Za-z0-9_./-]+',values['UNYRI_CADDYFILE']), 'Use a simple absolute Linux file path without spaces or colon'
for component in ['postgres','redis','minio','caddy','coturn']:values[component.upper()+'_IMAGE']=getattr(args,component+'_image')
values.update({key:secrets.token_hex(32)for key in ['POSTGRES_PASSWORD','REDIS_PASSWORD','MINIO_ROOT_PASSWORD','JWT_SECRET','COCO_ADMIN_KEY','TURN_SHARED_SECRET','S3_SECRET_ACCESS_KEY','S3_UPLOAD_SECRET_ACCESS_KEY']})
values.update(MINIO_ROOT_USER='unyri_root_'+secrets.token_hex(8),S3_ACCESS_KEY_ID='unyri_reader_'+secrets.token_hex(8),S3_UPLOAD_ACCESS_KEY_ID='unyri_upload_'+secrets.token_hex(8))
fd=os.open(output,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
with os.fdopen(fd,'w',encoding='utf8',newline='\n')as f:
 os.fchmod(f.fileno(),0o600)
 for key,value in values.items():f.write(key+'='+value+'\n')
 f.flush();os.fsync(f.fileno())
print(json.dumps({'created':True,'mode':'0600','secretsPrinted':False,'networkOrContainersStarted':False,'S3PrincipalsStillRequireProvisioning':True}))
