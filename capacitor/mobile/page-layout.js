export const paperSizes={A5:[148,210],A4:[210,297],A3:[297,420],Letter:[215.9,279.4],Legal:[215.9,355.6]};
export function pageFrame(size,orientation,width,height){
 const paper=paperSizes[size];if(!paper||!['portrait','landscape'].includes(orientation))throw Error('Choose a page size and orientation.');
 let [w,h]=paper.map(mm=>mm*72/25.4);if(orientation==='landscape')[w,h]=[h,w];
 // Matches the PDF's 30 pt side margins, 44 pt header and 50 pt footer.
 const ratio=(w-60)/(h-94),availableW=width*.84,availableH=height*.84;
 const frameW=Math.min(availableW,availableH*ratio),frameH=frameW/ratio;
 return [(.5-frameW/width/2),(.5-frameH/height/2),(.5+frameW/width/2),(.5+frameH/height/2)];
}
