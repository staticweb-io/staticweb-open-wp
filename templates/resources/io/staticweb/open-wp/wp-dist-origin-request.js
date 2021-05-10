/* Check whether the EC2 instance is running. If so, tell CloudFront
 * to use its current IP as the origin. If not, start the instance or
 * wait for it to start and show a loading page.
*/

const AWS = require("aws-sdk");

const logs = new AWS.CloudWatchLogs({apiVersion: '2014-03-28', region: process.env.AWS_REGION});
const ec2 = new AWS.EC2({apiVersion: '2016-11-15', region: INSTANCE_REGION});
const describeParams = {InstanceIds: [INSTANCE_ID]};

const staticFileDomain = "static.staticweb.io";

const customAuthHeader = [{
  key: 'StaticWeb-CloudFront-Authorization',
  value: AUTH_HEADER
}];

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

var cDI = Promise.resolve(null);

const getInstance = (describeData) => {
  const reservs = describeData.Reservations[0];
  if (reservs) return reservs.Instances[0];
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

exports.handler = async (event, context) => {
  const record = event.Records[0].cf;
  const request = record.request;
  const customOrigin = request.origin.custom;
  const di = await describeInstance();
  const instance = di.data ? getInstance(di.data) : null;
  await sLR(context.logGroupName);
  if (instance && 16 == instance.State.Code) {
    customOrigin.domainName = instance.PublicDnsName;
    customOrigin.customHeaders['staticweb-cloudfront-authorization'] = customAuthHeader;
  } else {
    customOrigin.domainName = staticFileDomain;
    customOrigin.port = 443
    customOrigin.protocol = "https";
    customOrigin.sslProcotols = ["TLSV1.2"];
    const h = request.headers;
    delete h.authorization;
    delete h.cookie;
    h.host[0].value = staticFileDomain;
    request.querystring = "";
    if ( di.err ) {
      request.uri = "/staticweb-open-wp/error-describe-instances.html";
    } else if ( ! instance ) {
      request.uri = "/staticweb-open-wp/instance-not-found.html";
    } else {
      request.uri = "/staticweb-open-wp/starting-instance.html";
    }
  }

  console.log("Routing to " + customOrigin.protocol + "://" + customOrigin.domainName + customOrigin.path + request.uri);

  return request;
};
