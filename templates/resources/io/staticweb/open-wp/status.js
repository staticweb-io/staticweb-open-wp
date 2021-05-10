/* Check whether the EC2 instance is running. If so, tell CloudFront
 * to use its current IP as the origin. If not, start the instance or
 * wait for it to start and show a loading page.
*/

const AWS = require("aws-sdk");

const logs = new AWS.CloudWatchLogs({apiVersion: '2014-03-28', region: process.env.AWS_REGION});
const ec2 = new AWS.EC2({apiVersion: '2016-11-15', region: INSTANCE_REGION});
const describeParams = {InstanceIds: [INSTANCE_ID]};
const startParams = describeParams;

var cSLR =Promise.resolve(false);
const sLR=async(lGN)=>{
const cached=await cSLR;
if(cached)return cached;
console.log("Setting log retention to 14 days.");
cSLR=new Promise((resolve,reject)=>{
logs.putRetentionPolicy({logGroupName:lGN,retentionInDays:14},
function(err,data) {
if(err){
console.error(err);
}
resolve(true);
}
);
});
return await cSLR;
};

var cDI=Promise.resolve(null);
var cSI=Promise.resolve(null);

const getInstance = (describeData) => {
  const reservs = describeData.Reservations[0];
  if (reservs) return reservs.Instances[0];
};

const startInstance = async() => {
  const si = await cSI;
  const elapsed = si ? new Date().getTime() - si.time : null;
  if ( si && 5000 >= elapsed ) {
    console.log("startInstance cache hit");
    return si;
  }

  cSI=new Promise((resolve, reject) => {
    ec2.startInstances(startParams, function(err, data) {
      const time = new Date().getTime();
      if (err) {
        console.error("Error starting instance: " + JSON.stringify(err));
        resolve({err: err, time: time});
      } else {
        console.log("Starting instance");
        resolve({data: data, time: time});
      }
    });
  });
  return await cSI;
};

const describeInstance = async() => {
  const di = await cDI;
  const elapsed = di ? new Date().getTime() - di.time : null;
  if ( di && 2000 >= elapsed ) {
    console.log("describeInstance cache hit");
    return di;
  }
  
  cDI = new Promise((resolve, reject) => {
    ec2.describeInstances(describeParams, function(err, data) {
      console.log("describeInstance cache miss");
      const time = new Date().getTime();
      if (err) {
        console.log(err);
        resolve({err: err, time: time});
      } else {
        resolve({data: data, time: time});
      }
    });
  });

  return await cDI;
};
const bad={status:400,statusDescription:'Bad Request'};
const errR={status:500,statusDescription:'Server Error'};
exports.handler = async (event, context) => {
  const record = event.Records[0].cf;
  const request = record.request;
  const di = await describeInstance();

  if ( di.err ) {
    return errR;
  }
  await sLR(context.logGroupName);
  const instance = getInstance(di.data);
  const state = instance.State.Name;

  console.log(JSON.stringify(event));

  if ( 'POST' == request.method ) {
    const reqBody = Buffer.from(request.body.data, 'base64').toString();
    try {
      const jsBody = JSON.parse(reqBody);
      if ( jsBody && typeof jsBody === 'object' && jsBody['start'] ) {
        const start = jsBody['start'];
        if ( typeof start !== 'boolean' ) {
          return bad;
        } else if ( start && 80 === instance.State.Code ) {
          const si = await startInstance();
          if ( si.err ) {
            return errR;
          }
        }
      }
    } catch (e) {
      return bad;
    }
  }

  const body = ('running' === state) ? {
    state: state,
    ready: true,
    messageHtml: 'Waiting for server response...'
  } : {
    state: state,
    ready: false,
    messageHtml: 'Starting your server...'
  };

  return {
    body: JSON.stringify(body),
    bodyEncoding: 'text',
    status: 200,
    statusDescription: 'OK',
    headers: {
      "content-type": [{
        key: 'Content-Type',
        value: 'application/json'
      }]
    }
  };
};
