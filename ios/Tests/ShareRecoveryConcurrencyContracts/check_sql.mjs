// Node 22.13+ supplement. All publication/guard/compaction/reclamation SQL
// comes from production; the Swift runner also executes the real callers.
import {DatabaseSync} from 'node:sqlite';
import {readFileSync, mkdtempSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve, dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../../..');
const source=readFileSync(join(root,'ios/UNYRI/Data/AttachmentStore.swift'),'utf8');
const migration=source.slice(source.indexOf('private func relocateDraftReferencesAfterContainerMove('),source.indexOf('func upsert('));
const purges=source.slice(source.indexOf('func deleteRecords('),source.indexOf('func cachedPathsAsync('));
const gc=source.slice(source.indexOf('private func reclaimFenceBatch('),source.indexOf('// MARK: - Writes'));
const schemaStart=source.indexOf('PRAGMA journal_mode=WAL;');
const schema=source.slice(schemaStart,source.indexOf('"""',schemaStart));
const insert=source.match(/let sql = """([\s\S]*?)"""/)[1];
const sourceGuard=source.match(/let sourceGuard = """([\s\S]*?)"""/)[1];
const staging=migration.match(/try execute\("(PRAGMA temp_store=FILE; CREATE TEMP TABLE[^"\n]+)"\)/)[1];
const proposal=migration.match(/withStatement\("(INSERT INTO temp\.draft_reference_repairs[^"\n]+)"\)/)[1];
const update=migration.match(/try execute\("""([\s\S]*?)"""\)/)[1];
const marker=migration.match(/withStatement\("(INSERT OR REPLACE INTO attachment_store_metadata[^"\n]+)"\)/)[1];
const messageFence=purges.match(/"(INSERT INTO deleted_attachment_messages[^"\n]+)"/)[1];
const messageDelete=purges.match(/"(DELETE FROM attachments WHERE client_message_id[^"\n]+)"/)[1];
const chatFence=purges.match(/"(INSERT OR IGNORE INTO deleted_chats[^"\n]+)"/)[1];
const chatDelete=purges.match(/"(DELETE FROM attachments WHERE chat_id[^"\n]+)"/)[1];
const cutoff=purges.match(/"(INSERT INTO attachment_clear_cutoffs[^"\n]+)"/)[1];
const compact=purges.match(/"(DELETE FROM deleted_attachment_messages WHERE chat_id=\? AND sequence[^"\n]+)"/)[1];
const fenceDelete='DELETE FROM deleted_attachment_messages WHERE client_message_id=?;';
assert(source.includes('"'+fenceDelete+'"'));
const chatFences=purges.match(/"(DELETE FROM deleted_attachment_messages WHERE chat_id=\?;)"/)[1];
const cutoffDelete=purges.match(/"(DELETE FROM attachment_clear_cutoffs WHERE chat_id=\?;)"/)[1];
const gcStage=gc.match(/try execute\("(PRAGMA temp_store=FILE; DROP TABLE IF EXISTS temp\.attachment_cutoff_snapshot[^"\n]+)"\)/)[1];
const gcImport=gc.match(/try execute\("(INSERT INTO attachment_clear_cutoffs[^"\n]+)"\)/)[1];
const gcTemp=gc.match(/try execute\("""([\s\S]*?)"""\)/)[1];
const gcCandidates=gc.match(/withStatement\("""([\s\S]*?)"""\)/)[1];
const covered=gc.match(/"(SELECT EXISTS\(SELECT 1 FROM deleted_chats[^"\n]+)"/)[1];
const folder=mkdtempSync(join(tmpdir(),'unyri-share-sql-'));
let main,scan,live,messages,checks=0;
const check=(value,reason)=>{assert(value,reason);checks++;};
try {
 const database=join(folder,'attachments.sqlite3'), messageFile=join(folder,'messages.sqlite3');
 messages=new DatabaseSync(messageFile);
 messages.exec('PRAGMA journal_mode=WAL; CREATE TABLE messages(client_message_id TEXT PRIMARY KEY,chat_id TEXT,sequence INTEGER,kind TEXT,hidden_locally INTEGER DEFAULT 0,delete_sequence INTEGER DEFAULT 0); CREATE TABLE chat_workspace(chat_id TEXT,name TEXT,value TEXT,PRIMARY KEY(chat_id,name)); CREATE TABLE deleted_chats(chat_id TEXT PRIMARY KEY);');
 main=new DatabaseSync(database);main.exec(schema);
 scan=new DatabaseSync(database);live=new DatabaseSync(database);
 for(const db of [main,scan,live]){db.exec('PRAGMA busy_timeout=0');db.prepare('ATTACH DATABASE ? AS attachment_messages').run(pathToFileURL(messageFile).href+'?mode=ro');}
 const values=(id,p=null,c='chat',client=id,seq=null)=>[{source_chat:c,source_message:client,source_sequence:seq},id,client,c,'voice','audio/mp4',null,3,null,null,42,null,p,'downloaded','uploaded',100,'voice/'+id,31,1,null,null,null,'fixture'];
 const write=(db,id,p=null,c='chat',client=id,seq=null,strict=false)=>db.prepare(insert+(strict?'\n'+sourceGuard:'')+';').run(...values(id,p,c,client,seq));
 const sourceRow=(id,seq=1,c='chat')=>{messages.prepare('INSERT OR REPLACE INTO messages(client_message_id,chat_id,sequence,kind) VALUES (?,?,?,?)').run(id,c,seq,'content');messages.prepare('INSERT OR REPLACE INTO chat_workspace VALUES (?,?,?)').run(c,'restore_attachment:'+id,'fixture');};
 const count=(table)=>main.prepare('SELECT count(*) n FROM '+table).get().n;
 const clear=(ids,c,seq)=>{main.exec('BEGIN IMMEDIATE');try{main.prepare(cutoff).run(c,seq,c);main.prepare(compact).run(c,c);for(const id of ids){main.prepare(fenceDelete).run(id);main.prepare(messageDelete).run(id);}main.exec('COMMIT');}catch(e){main.exec('ROLLBACK');throw e;}};
 const reclaim=(active=[])=>{
  let turns=0;
  while(true){scan.exec(gcStage);scan.exec(gcImport);scan.exec(gcTemp);for(const id of active)scan.prepare('INSERT INTO temp.active_metadata_sources VALUES (?)').run(id);
   const ids=scan.prepare(gcCandidates).all(null,null);if(!ids.length)break;let removed=0;scan.exec('BEGIN IMMEDIATE');
   try{for(const row of ids){const id=row.client_message_id;if(active.includes(id))continue;const j=scan.prepare('SELECT chat,sequence FROM temp.attachment_journal_sources WHERE message=?').get(id);if(j&&!scan.prepare(covered).get(j.chat,id,j.sequence,j.chat)[Object.keys(scan.prepare(covered).get(j.chat,id,j.sequence,j.chat))[0]])continue;removed+=scan.prepare(fenceDelete).run(id).changes;}scan.exec('COMMIT');}catch(e){scan.exec('ROLLBACK');throw e;}
   turns++;if(ids.length<256||!removed)break;assert(turns<1000,'reclamation loop');
  }return turns;
 };
 // Real concurrent writes while the production repair plans live in TEMP.
 for(const id of ['voice','raced','deleted'])write(main,id,'/old/draft-media/'+id+'/voice.m4a');
 scan.exec(staging);
 for(const r of scan.prepare('SELECT rowid,attachment_id,local_path FROM attachments').all())scan.prepare(proposal).run(r.rowid,r.attachment_id,r.local_path,r.local_path.replace('/old/','/new/'));
 write(live,'incoming');check(main.prepare('SELECT 1 FROM attachments WHERE attachment_id=?').get('incoming'),'live staging write');
 live.prepare('UPDATE attachments SET local_path=? WHERE attachment_id=?').run('/live/voice.m4a','raced');live.prepare('DELETE FROM attachments WHERE attachment_id=?').run('deleted');
 const withRowid=insert.replace('(attachment_id,','(rowid,attachment_id,').replace('SELECT ?,','SELECT ?, ?,');
 const reuse=values('replacement','/old/draft-media/deleted/voice.m4a');live.prepare(withRowid).run(reuse[0],3,...reuse.slice(1));
 const publish=()=>{scan.exec('BEGIN IMMEDIATE');try{scan.exec(update);scan.prepare(marker).run('/account');scan.exec('COMMIT');}catch(e){scan.exec('ROLLBACK');throw e;}};
 publish();
 check(main.prepare('SELECT local_path p FROM attachments WHERE attachment_id=?').get('voice').p.startsWith('/new/'),'repair published');
 check(main.prepare('SELECT local_path p FROM attachments WHERE attachment_id=?').get('raced').p==='/live/voice.m4a','live edit wins');
 check(!main.prepare('SELECT 1 FROM attachments WHERE attachment_id=?').get('deleted'),'no deleted-row resurrection');
 check(main.prepare('SELECT local_path p FROM attachments WHERE attachment_id=?').get('replacement').p.startsWith('/old/'),'rowid identity checked');
 check(main.prepare("SELECT value FROM attachment_store_metadata WHERE key='local_roots_v2'").get().value==='/account','marker published with repairs');
 live.prepare(insert.replace('INSERT OR REPLACE','INSERT OR IGNORE')).run(...values('voice'));
 check(main.prepare('SELECT local_path p FROM attachments WHERE attachment_id=?').get('voice').p!==null,'incoming echo retains existing original');
 main.prepare('UPDATE attachments SET local_path=? WHERE attachment_id=?').run('/old/draft-media/voice/voice.m4a','voice');
 main.exec("DELETE FROM attachment_store_metadata WHERE key='local_roots_v2'; CREATE TRIGGER reject_marker BEFORE INSERT ON attachment_store_metadata WHEN NEW.key='local_roots_v2' BEGIN SELECT RAISE(ABORT,'fixture'); END;");
 assert.throws(publish);checks++;check(main.prepare('SELECT local_path p FROM attachments WHERE attachment_id=?').get('voice').p.startsWith('/old/'),'atomic rollback');main.exec('DROP TRIGGER reject_marker');
 live.exec('BEGIN IMMEDIATE');assert.throws(publish);checks++;live.exec('ROLLBACK');publish();
 // Exact stale chat-check ordering, with the real atomic guard.
 check(!live.prepare('SELECT 1 FROM deleted_chats WHERE chat_id=?').get('gone'),'chat initially live');main.exec('BEGIN IMMEDIATE');main.prepare(chatFence).run('gone');main.prepare(chatDelete).run('gone');main.prepare(chatFences).run('gone');main.prepare(cutoffDelete).run('gone');main.exec('COMMIT');
 write(live,'late-chat',null,'gone');check(!main.prepare('SELECT 1 FROM attachments WHERE attachment_id=?').get('late-chat'),'chat tombstone wins');
 main.exec('BEGIN IMMEDIATE');main.prepare(messageFence).run('rolled-back-delete','rollback',7,'rollback');main.exec('ROLLBACK');
 write(live,'rolled-back-delete',null,'rollback','rolled-back-delete',7);check(main.prepare('SELECT 1 FROM attachments WHERE attachment_id=?').get('rolled-back-delete'),'failed purge does not publish a partial fence');
 main.exec('BEGIN IMMEDIATE');main.prepare(cutoff).run('rollback-cutoff',99,'rollback-cutoff');main.exec('ROLLBACK');
 check(!main.prepare('SELECT 1 FROM attachment_clear_cutoffs WHERE chat_id=?').get('rollback-cutoff'),'failed clear does not publish a partial cutoff');
 // Transient fences protect an unconsumed journal, then disappear.
 sourceRow('single',9,'individual');main.prepare(messageFence).run('single','individual',9,'individual');reclaim();check(count('deleted_attachment_messages')===1,'unconsumed journal retained');
 messages.prepare('DELETE FROM chat_workspace WHERE name=?').run('restore_attachment:single');reclaim(['single']);check(count('deleted_attachment_messages')===1,'pending ticket retained');reclaim();check(count('deleted_attachment_messages')===0,'consumed journal reclaimed');
 write(live,'single',null,'individual','single',9,true);check(!main.prepare('SELECT 1 FROM attachments WHERE attachment_id=?').get('single'),'consumed owner cannot replay after reclamation');
 // Repeated text clears retain a single monotonic cutoff, no message fences.
 for(let turn=1;turn<=3;turn++){const ids=Array.from({length:10000},(_,i)=>'text-'+turn+'-'+i);clear(ids,'bounded',turn*10000);check(count('deleted_attachment_messages')===0,'text clear creates no fences');}
 check(main.prepare('SELECT through_sequence n FROM attachment_clear_cutoffs WHERE chat_id=?').get('bounded').n===30000,'monotonic cutoff');
 sourceRow('old',1,'bounded');write(live,'reuse-id',null,'bounded','old',1,true);check(!main.prepare('SELECT 1 FROM attachments WHERE attachment_id=?').get('reuse-id'),'compaction blocks old sequence');
 sourceRow('new',30001,'bounded');write(live,'reuse-id',null,'bounded','new',30001,true);check(main.prepare('SELECT client_message_id c FROM attachments WHERE attachment_id=?').get('reuse-id').c==='new','same attachment ID admits new message');
 main.prepare(messageFence).run('new','bounded',30001,'bounded');main.exec('BEGIN IMMEDIATE');main.prepare(chatFence).run('bounded');main.prepare(chatDelete).run('bounded');main.prepare(chatFences).run('bounded');main.prepare(cutoffDelete).run('bounded');main.exec('COMMIT');
 check(count('deleted_attachment_messages')===0,'whole chat removes message fences');check(!main.prepare('SELECT 1 FROM attachment_clear_cutoffs WHERE chat_id=?').get('bounded'),'chat tombstone supersedes cutoff');
 write(live,'reuse-id',null,'bounded','new',30001,true);check(!main.prepare('SELECT 1 FROM attachments WHERE attachment_id=?').get('reuse-id'),'whole chat remains protected after compaction');
 // Upgrade cleanup is batched and preserves live replay owners.
 main.exec('BEGIN IMMEDIATE');for(let i=0;i<10000;i++)main.prepare('INSERT INTO deleted_attachment_messages(client_message_id) VALUES (?)').run('legacy-'+i);main.exec('COMMIT');
 check(reclaim()>1,'legacy launch sweep is batched');check(count('deleted_attachment_messages')===0,'orphan legacy fences reclaimed');
 console.log('Share recovery production SQLite SQL passed: '+checks+' checks');
}finally{for(const db of [live,scan,main,messages])db?.close();rmSync(folder,{recursive:true,force:true});}
