import * as fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';

const repository = fileURLToPath(new URL('../', import.meta.url));
const args = process.argv.slice(2);
const defaultProject = 'art/Denia-Blue.bbmodel';
const usage = `node tools/export-domain-outfit.mjs [Blockbench 工程] [输出资源根目录]（默认工程：${defaultProject}）`;
const faceNames = {north:'front', south:'back', east:'right', west:'left', up:'top', down:'bottom'};
const motions = {
    tail_left:'LEFT_TAIL', tail_right:'RIGHT_TAIL',
    tail_overlay_left:'LEFT_OVERLAY', tail_overlay_right:'RIGHT_OVERLAY',
    front_apron:'APRON', back_lock:'HAIR', back_lock_tip:'HAIR_TIP', brooch:'FIXED',
};
const zero = [0,0,0];

// The game supplies these bones and the standard slim skin UVs. Their geometry is preview-only.
// parent, pivot, base cuboid's min corner, size, and the two vanilla UV offsets.
const skeleton = new Map([
    ['Waist', [null,[0,12,0]]],
    ['Head', ['Waist',[0,24,0],[-4,24,-4],[8,8,8],[0,0],[32,0]]],
    ['Body', ['Waist',[0,24,0],[-4,12,-2],[8,12,4],[16,16],[16,32]]],
    ['Right Arm', ['Waist',[5,22,0],[4,12,-2],[3,12,4],[40,16],[40,32]]],
    ['Left Arm', ['Waist',[-5,22,0],[-7,12,-2],[3,12,4],[32,48],[48,48]]],
    ['Right Leg', [null,[1.9,12,0],[-.1,0,-2],[4,12,4],[0,16],[0,32]]],
    ['Left Leg', [null,[-1.9,12,0],[-3.9,0,-2],[4,12,4],[16,48],[0,48]]],
]);
function check(condition, message) {
    if (!condition) throw new Error(message);
}
function vec(value, length, label) {
    check(Array.isArray(value) && value.length === length && value.every(Number.isFinite), label+' 格式错误');
    return value;
}
function same(a,b) {
    return Array.isArray(a) && a.length === b.length && a.every((v,i)=>Math.abs(v-b[i]) < .0001);
}
function readTexture(texture, projectFile) {
    let data;
    const url = /^data:image\/png;base64,([A-Za-z0-9+/=\s]+)$/i.exec(texture.source ?? '');
    if (url) data = Buffer.from(url[1], 'base64');
    else {
        const filename = texture.relative_path;
        check(filename && !path.isAbsolute(filename), texture.name+' 请嵌入 PNG，或使用工程目录内的相对 PNG 文件');
        const folder = fs.realpathSync(path.dirname(projectFile));
        const file = fs.realpathSync(path.resolve(folder,filename));
        check(file.startsWith(folder+path.sep), texture.name+' 的外部贴图须位于工程目录内');
        data = fs.readFileSync(file);
    }
    check(data.length >= 45 && data.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex')) &&
        data.toString('ascii',12,16) === 'IHDR' && data.toString('ascii',data.length-8,data.length-4) === 'IEND',
        texture.name+' 的 PNG 文件无效');
    const width=data.readUInt32BE(16), height=data.readUInt32BE(20);
    check(width>0 && height>0, texture.name+' 尺寸无效');
    return {data,width,height};
}
function indexById(list) {
    const byId=new Map();
    for(const entry of list){
        check(typeof entry.uuid==='string' && !byId.has(entry.uuid),'工程含重复或缺失的 UUID');
        byId.set(entry.uuid,entry);
    }
    return byId;
}
function exportProject(file) {
    const project=JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
    const version=Number.parseInt(project.meta?.format_version,10);
    check([4,5].includes(version) && project.meta.model_format==='free','需要 Blockbench 4.x/5.x 的 Generic Model 工程');
    check(!project.animations?.length,'模组动作由 Java 驱动，当前导出器无法导出 Blockbench 时间轴动画');
    const textures=project.textures;
    check(Array.isArray(textures) && textures.length===2,'工程需要两张贴图：girlfriend2.png 与 domain_outfit.png');
    function textureNamed(name){
        const matches=textures.filter(t=>t.name===name);
        check(matches.length===1,'未找到唯一贴图 '+name);
        return matches[0];
    }
    const base=textureNamed('girlfriend2.png'), trim=textureNamed('domain_outfit.png');
    const baseImage=readTexture(base,file), trimImage=readTexture(trim,file);
    const uvSize=t=>[t.uv_width??project.resolution?.width,t.uv_height??project.resolution?.height];
    check(same(uvSize(base),[64,64]) && baseImage.width===baseImage.height && baseImage.width%64===0,
        '基础皮肤须保留 64×64 UV 空间以及等比例的方形 PNG');
    const [uw,uh]=vec(uvSize(trim),2,'附件 UV 尺寸');
    check(uw>0 && uh>0,'附件 UV 尺寸无效');
    const pool=indexById(project.elements), groupData=indexById(project.groups??[]);
    const seen=new Set(), runtime=[], foundBones=new Set(), foundCubes=new Set(), foundParts=new Set();

    function uses(face,texture) {
        if(face?.texture==null)return false;
        const ref=face.texture;
        return typeof ref==='number' ? ref===textures.indexOf(texture) : ref===texture.uuid || ref===String(texture.id);
    }
    function baseCube(cube,bone){
        const [, ,corner,size,uv0,uv1]=skeleton.get(bone);
        const outer=cube.name===(bone==='Head'?'Hat Layer':bone+' Layer');
        check(corner && (outer || cube.name===bone), '新增方块 '+cube.name+' 须放在 Body 的一个附件组内');
        const expectedTop=corner.map((n,i)=>n+size[i]);
        const inflation=outer?(bone==='Head'?.5:.25):0;
        check(!foundCubes.has(cube.name) && same(cube.from,corner) && same(cube.to,expectedTop)
            && same(cube.uv_offset??[0,0],outer?uv1:uv0) && cube.box_uv!==false && !cube.mirror_uv
            && Math.abs((cube.inflate??0)-inflation)<.0001 && Object.keys(faceNames).every(f=>uses(cube.faces?.[f],base)),
            '原版细臂骨架及双层 UV 由游戏提供，无法导出这项原版方块修改：'+cube.name);
        foundCubes.add(cube.name);
    }
    function accessory(cube, group) {
        check(!(cube.box_uv??project.meta.box_uv) && !cube.inflate, cube.name+' 需要逐面 UV，膨胀值应为 0');
        const from=vec(cube.from,3,cube.name), to=vec(cube.to,3,cube.name);
        const size=to.map((n,i)=>n-from[i]);
        check(size.every(n=>n>0),cube.name+' 需要有厚度的正尺寸方块');
        const [px,py,pz]=group.pivot;
        const box=[-to[0]-px,24-to[1]-py,from[2]-pz,...size], faces={};
        for (const [bb,side] of Object.entries(faceNames)) {
            const face=cube.faces?.[bb];
            check(uses(face,trim) && !(face.rotation%360), cube.name+'.'+bb+' 须使用附件贴图和未旋转的矩形 UV');
            let [u,v,U,V]=vec(face.uv,4,cube.name+'.'+bb);
            if (bb==='up'||bb==='down') [u,v,U,V]=[U,V,u,v];
            check(Math.min(u,U)>=-.0001 && Math.max(u,U)<=uw+.0001 && Math.min(v,V)>=-.0001 && Math.max(v,V)<=uh+.0001,
                cube.name+'.'+bb+' 超出附件贴图 UV 边界');
            faces[side]=[u*trimImage.width/uw,v*trimImage.height/uh,U*trimImage.width/uw,V*trimImage.height/uh];
        }
        group.boxes.push({name:cube.name,box,faces});
    }
    function visit(node,parent=null,group=null){
        const id=typeof node==='string'?node:node.uuid;
        check(id && !seen.has(id),'层级引用缺失或重复：'+id);
        seen.add(id);
        if(typeof node==='string'){
            const cube=pool.get(id);
            check(cube?.type==='cube' && !cube.rescale && same(cube.rotation??zero,zero) &&
                same(cube.scale??[1,1,1],[1,1,1]) && cube.export!==false,
                '仅支持未单独旋转且可导出的方块：'+(cube?.name??id));
            if(group) accessory(cube,group);
            else {check(skeleton.has(parent),'方块须位于骨骼或附件组内');baseCube(cube,parent);}
            return;
        }
        const item=groupData.get(id)??node;
        check(item.name && Array.isArray(node.children) && item.export!==false && same(item.scale??[1,1,1],[1,1,1]),
            '无效或存在缩放的组：'+id);
        const origin=vec(item.origin,3,item.name), rotation=vec(item.rotation??zero,3,item.name);
        if(skeleton.has(item.name)){
            const [expectedParent,pivot]=skeleton.get(item.name);
            check(!foundBones.has(item.name) && parent===expectedParent && same(origin,pivot) && same(rotation,zero),
                '原版骨骼层级、枢轴或静态旋转已改变：'+item.name);
            foundBones.add(item.name);
            node.children.forEach(child=>visit(child,item.name));
        }else{
            check(parent==='Body' && !group,'附件组须直接位于 Body 下：'+item.name);
            const motion=Object.hasOwn(motions,item.name)?motions[item.name]:item.name.startsWith('fixed_')?'FIXED':null;
            check(motion && !foundParts.has(item.name),'未知或重复附件组 '+item.name+'；新的固定装饰请使用 fixed_ 名称');
            foundParts.add(item.name);
            const [x,y,z]=origin, [a,b,c]=rotation;
            const part={name:item.name,parent:'body',motion,pivot:[-x,24-y,z],rotationDeg:[-a,-b,c],boxes:[]};
            runtime.push(part);
            node.children.forEach(child=>visit(child,item.name,part));
        }
    }
    project.outliner.forEach(node=>visit(node));
    check(!foundParts.has('back_lock_tip') || foundParts.has('back_lock'),
        'back_lock_tip 需要 back_lock 提供发根的转动枢轴');
    check(foundBones.size===skeleton.size && foundCubes.size===12,'原版 Alex 模型须包含七个骨骼与十二个内外层方块');
    check([...pool.keys(),...groupData.keys()].every(id=>seen.has(id)),'工程包含未挂入层级树的方块或组');
    const model={width:trimImage.width,height:trimImage.height,groups:runtime};
    return new Map([
        ['textures/entity/girlfriend2.png',baseImage.data],
        ['textures/entity/domain_outfit.png',trimImage.data],
        ['models/entity/domain_outfit.json',JSON.stringify(model,(_key,v)=>typeof v==='number'?Number(v.toFixed(8)):v,2)+'\n'],
    ]);
}

function publish(originalRoot, outputs){
    fs.mkdirSync(originalRoot,{recursive:true});
    const root=fs.realpathSync(originalRoot);
    const lock=path.join(root,'.domain-outfit-export.lock');
    fs.closeSync(fs.openSync(lock,'wx'));
    const records=[], tag='.'+randomUUID();
    try{
        for(const [relative,data] of outputs){
            let dir=root;
            for(const component of path.dirname(relative).split(/[\\/]/)){
                dir=path.join(dir,component);
                if(!fs.existsSync(dir))fs.mkdirSync(dir);
                check(fs.lstatSync(dir).isDirectory(),'输出目录必须为实际目录：'+dir);
            }
            const target=path.join(root,relative), old=fs.lstatSync(target,{throwIfNoEntry:false});
            check(!old || old.isFile(),'输出位置必须是普通文件：'+target);
            const record={target,temp:target+tag+'.new',backup:target+tag+'.old',old,committed:false};
            records.push(record);
            fs.writeFileSync(record.temp,data,{flag:'wx'});
            if(old)fs.copyFileSync(target,record.backup,fs.constants.COPYFILE_EXCL);
        }
        for(const item of records){fs.renameSync(item.temp,item.target);item.committed=true;}
    }catch(error){
        const recovery=[];
        for(const item of records.filter(r=>r.committed).reverse()){
            try{
                if(item.old)fs.renameSync(item.backup,item.target);
                else fs.unlinkSync(item.target);
            }catch{
                item.keepBackup=true;
                recovery.push(item.old?item.backup:item.target);
            }
        }
        if(recovery.length)throw new Error(error.message+'。恢复被其他进程阻止。请检查 '+recovery.join('、'),{cause:error});
        throw error;
    }finally{
        for(const item of records){
            fs.rmSync(item.temp,{force:true});
            if(!item.keepBackup)fs.rmSync(item.backup,{force:true});
        }
        fs.unlinkSync(lock);
    }
}
try{
    if(args.includes('--help')||args.includes('-h'))console.log(usage);
    else{
        check(args.length<=2,usage);
        const file=path.resolve(args[0]??path.join(repository,defaultProject));
        const root=path.resolve(args[1]??path.join(repository,'src/main/resources/assets/mcgf'));
        const outputs=exportProject(file);
        publish(root,outputs);
        for(const name of outputs.keys())console.log(path.join(root,name));
    }
}catch(error){
    console.error('导出失败：'+error.message);
    process.exitCode=1;
}
