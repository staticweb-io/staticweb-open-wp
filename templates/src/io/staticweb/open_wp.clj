(ns io.staticweb.open-wp
  (:require [clojure.java.io :as io])
  (:use io.staticweb.cloudformation-templating)
  (:refer-clojure :exclude [ref]))

(deftemplate open-wp-template
  :Description
  "This template creates an EC2 instance with a secured WordPress install configured for static deployment as well as an S3 bucket and a CloudFront distribution to serve the static files."

  :Parameters
  {:CloudFrontAuthorizationHeader
   {:AllowedPattern "Bearer [A-Za-z\\d+/=_\\-]+"
    :ConstraintDescription "Must start with \"Bearer \" and be 32-128 characters long. The characters after \"Bearer \" must be alphanumeric or /, +, =, _, or -"
    :Description "Authorization header to add to CloudFront requests so that the WordPress server will accept the request."
    :MaxLength 128
    :MinLength 32
    :Type "String"}
   :KeyName
   {:ConstraintDescription "Must be the name of an existing EC2 KeyPair."
    :Description "Name of an existing EC2 KeyPair to enable SSH access to the instances"
    :Type "String"}
   :LiveSiteCertificateArn
   {:Description
    "Arn of the certificate for a custom live site domain."
    :Type "String"}
   :LiveSiteDomainName
   {:Description "Fully-qualifed domain name for the live site, if using a custom domain."
    :Type "String"}
   :UserPass
   {:AllowedPattern "\\\\\\$P\\\\\\$.+"
    :ConstraintDescription "Must be in PHPass format with the $s escaped, e.g., \"\\$P\\$BfxyjxCkv2XmidTZjA2lV6wYPYZcXi.\""
    :Description "Salted hash of the initial user password. You should set a new password after you log in."
    :MaxLength 36
    :MinLength 36
    :Type "String"}
   :WordPressAMI
   {:Description "AMI to use for the WordPress server."
    :Type "String"
    :Default "ami-070b9006b2d500921"}
   :WPCertificateArn
   {:Description
    "Arn of the certificate for the WordPress admin domain."
    :Type "String"}
   :WPDomainName
   {:Description "Fully-qualifed domain name for the WordPress admin, if using a custom domain."
    :Type "String"}}

  :Conditions
  {:UsingGenericLiveDomain
   (equals "" (ref :LiveSiteCertificateArn))
   :UsingGenericWPDomain
   (equals "" (ref :WPCertificateArn))
   :UsingKeyName
   (not-equals "" (ref :KeyName))}

  :Mappings
  {:RegionMap
   {:us-east-1
    {:ELBAccountId "127311923021"}}}

  :Resources
  {:LoggingBucket
   {:Type "AWS::S3::Bucket"
    :Properties
    {:AccessControl "Private"
     :BucketEncryption
     {:ServerSideEncryptionConfiguration
      [{:BucketKeyEnabled true
        :ServerSideEncryptionByDefault
        {:SSEAlgorithm "AES256"}}]}
     :LifecycleConfiguration
     {:Rules
      [{:ExpirationInDays 365
        :Status "Enabled"
        :Transitions
        [{:StorageClass "GLACIER"
          :TransitionInDays 1}]}]}
     :PublicAccessBlockConfiguration
     {:BlockPublicAcls true
      :BlockPublicPolicy true
      :IgnorePublicAcls true
      :RestrictPublicBuckets true}}}

   :LoggingBucketPolicy
   {:Type "AWS::S3::BucketPolicy"
    :Properties
    {:Bucket (ref :LoggingBucket)
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Principal
        {:AWS
         (join ""
           ["arn:aws:iam::"
            (find-in-map "RegionMap" region "ELBAccountId")
            ":root"])}
        :Action "s3:PutObject"
        :Resource
        (join ""
          ["arn:aws:s3:::"
           (ref :LoggingBucket)
           "/AWSLogs/"
           account-id
           "/*"])}
       {:Effect "Allow"
        :Principal {:Service "delivery.logs.amazonaws.com"}
        :Action "s3:PutObject"
        :Resource
        (join ""
          ["arn:aws:s3:::"
           (ref :LoggingBucket)
           "/AWSLogs/"
           account-id
           "/*"])
        :Condition
        {:StringEquals {:s3:x-amz-acl "bucket-owner-full-control"}}}
       {:Effect "Allow"
        :Principal {:Service "delivery.logs.amazonaws.com"}
        :Action "s3:GetBucketAcl"
        :Resource
        (join ""
          ["arn:aws:s3:::" (ref :LoggingBucket)])}]}}}

   :StaticSiteBucket
   {:Type "AWS::S3::Bucket"
    :Properties
    {:AccessControl "PublicRead"
     :WebsiteConfiguration
     {:IndexDocument "index.html"
      :ErrorDocument "error.html"}}}

   :StaticSiteBucketPolicy
   {:Type "AWS::S3::BucketPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Sid "PublicReadForGetBucketObjects"
        :Effect "Allow"
        :Principal "*"
        :Action "s3:GetObject"
        :Resource
        (join "" ["arn:aws:s3:::" (ref :StaticSiteBucket) "/*"])}]}
     :Bucket (ref :StaticSiteBucket)}}

   :StaticSiteDistribution
   {:Type "AWS::CloudFront::Distribution"
    :Properties
    {:DistributionConfig
     {:Aliases (fn-if :UsingGenericLiveDomain
                 no-value
                 [(ref :LiveSiteDomainName)])
      :DefaultCacheBehavior
      {:AllowedMethods ["GET" "HEAD"]
       :Compress true
       :ForwardedValues
       {:Cookies {:Forward "none"}
        :QueryString true}
       :TargetOriginId "StaticSiteBucketOrigin"
       :ViewerProtocolPolicy "redirect-to-https"}
      :Enabled true
      :HttpVersion "http2"
      :Logging
      {:Bucket (get-att :LoggingBucket "DomainName")
       :IncludeCookies false
       :Prefix "static-site/"}
      :Origins
      [{:CustomOriginConfig {:OriginProtocolPolicy "http-only"}
        :DomainName
        (join ""
          [(ref :StaticSiteBucket)
           ".s3-website-" region ".amazonaws.com"])
        :Id "StaticSiteBucketOrigin"}]
      :ViewerCertificate
      (fn-if :UsingGenericLiveDomain
        no-value
        {:AcmCertificateArn (ref :LiveSiteCertificateArn)
         :SslSupportMethod "sni-only"})}}}

   :S3AuthorAccessPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Action ["s3:GetBucketLocation"]
        :Resource
        [(join ""
           ["arn:aws:s3:::" (ref :StaticSiteBucket)])]}
       {:Effect "Allow"
        :Action
        ["s3:PutObject"
         "s3:GetObject"
         "s3:DeleteObject"
         "s3:PutObjectAcl"]
        :Resource
        [(join ""
           ["arn:aws:s3:::"
            (ref :StaticSiteBucket)
            "/*"])]}]}}}

   :CFInvalidationPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Action
        ["cloudfront:ListInvalidations"
         "cloudfront:GetInvalidation"
         "cloudfront:CreateInvalidation"]
        :Resource
        [(join ""
           ["arn:aws:cloudfront::"
            account-id
            ":distribution/"
            (ref :StaticSiteDistribution)])]}]}}}

   :StackDescribePolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Action "cloudformation:DescribeStacks"
        :Resource stack-id}]}}}

   :WPServerRole
   {:Type "AWS::IAM::Role"
    :Properties
    {:AssumeRolePolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Principal {:Service ["ec2.amazonaws.com"]}
        :Action ["sts:AssumeRole"]}]}
     :Path "/"
     :ManagedPolicyArns
     ["arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
      (ref :S3AuthorAccessPolicy)
      (ref :CFInvalidationPolicy)
      (ref :StackDescribePolicy)]}}

   :WPServerSecurityGroup
   {:Type "AWS::EC2::SecurityGroup"
    :Properties
    {:GroupDescription "Allow HTTP(S) access."
     :SecurityGroupIngress
     [{:CidrIp "0.0.0.0/0"
       :IpProtocol "tcp"
       :FromPort 80
       :ToPort 80}
      {:CidrIpv6 "::/0"
       :IpProtocol "tcp"
       :FromPort 80
       :ToPort 80}]}}

   :WPServerInstanceProfile
   {:Type "AWS::IAM::InstanceProfile"
    :Properties
    {:Path "/StaticWPServers/"
     :Roles [(ref :WPServerRole)]}}

   :WPServer
   {:Type "AWS::EC2::Instance"
    :Properties
    {:BlockDeviceMappings
     [{:DeviceName "/dev/xvda"
       :Ebs
       {:DeleteOnTermination true
        :Encrypted true
        :VolumeType "gp3"}}]
     :IamInstanceProfile (ref :WPServerInstanceProfile)
     :ImageId (ref :WordPressAMI)
     :InstanceType "t2.micro"
     :KeyName (fn-if :UsingKeyName (ref :KeyName) no-value)
     :SecurityGroups [(ref :WPServerSecurityGroup)]
     :UserData
     (user-data
       "#!/bin/bash -xe\n\n"
       "cp /home/admin/.ssh/authorized_keys /home/staticweb/.ssh\n"
       "chown staticweb:staticweb /home/staticweb/.ssh/authorized_keys\n"
       "echo \""
       "{:bucket-name \\\"" (ref :StaticSiteBucket) "\\\"\n"
       " :cloudfront-auth-header \\\"" (ref :CloudFrontAuthorizationHeader) "\\\"\n"
       " :cloudfront-distribution \\\"" (ref :StaticSiteDistribution) "\\\"\n"
       " :cloudfront-domain-name \\\""
       (if :UsingGenericLiveDomain
         (get-att :StaticSiteDistribution "DomainName")
         (ref :LiveSiteDomainName))
       "\\\"\n"
       " :create-jobs? true\n"
       " :region \\\"" region "\\\"\n"
       " :stack-id \\\"" stack-id "\\\"\n"
       " :user-pass \\\"" (ref :UserPass)
       "\\\"}\n\" > /opt/staticweb/init-deploy.edn\n"
       "bash /opt/staticweb/static-wp-daemon/init-deploy.sh\n"
       "/usr/local/bin/cfn-signal -e $?"
       " --stack " stack-name
       " --resource WPServer "
       " --region " region "\n")
     :Tags
     (tags
       :Name stack-name
       "staticweb:stack-id" stack-id)}
    :CreationPolicy {:ResourceSignal {:Timeout "PT15M"}}}

   :WPServerLifecyclePolicy
   {:Type "AWS::DLM::LifecyclePolicy"
    :Properties
    {:Description "WordPress Server Backups"
     :ExecutionRoleArn
     (join ""
       ["arn:aws:iam::" account-id
        ":role/service-role/AWSDataLifecycleManagerDefaultRole"])
     :PolicyDetails
     {:ResourceTypes ["INSTANCE"]
      :TargetTags (tags "staticweb:stack-id" stack-id)
      :Schedules
      [{:Name "12 Hour Schedule"
        :CreateRule
        {:Interval 12
         :IntervalUnit "HOURS"
         :Times ["09:00"]}
        :RetainRule {:Count 730}
        :CopyTags true
        :TagsToAdd (tags "staticweb:stack-id" stack-id)
        :VariableTags (tags :instance-id "$(instance-id)")}]}
     :State "ENABLED"}}

   :WPDistOriginRequestPolicy
   {:Type "AWS::IAM::ManagedPolicy"
    :Properties
    {:PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action "ec2:DescribeInstances"
        :Effect "Allow"
        :Resource "*"}
       {:Action ["ec2:StartInstances"
                 "ec2:StopInstances"]
        :Effect "Allow"
        :Resource
        (join ""
          ["arn:aws:ec2:" region ":" account-id ":"
           "instance/" (ref :WPServer)])}
       {:Action ["logs:PutRetentionPolicy"]
        :Effect "Allow"
        :Resource
        (join ""
          ["arn:aws:logs:*:" account-id ":log-group:/aws/lambda/us-east-1."
           stack-name "-WPDistOriginRequestFunction-*"])}
       {:Action ["logs:PutRetentionPolicy"]
        :Effect "Allow"
        :Resource
        (join ""
          ["arn:aws:logs:*:" account-id ":log-group:/aws/lambda/us-east-1."
           stack-name "-WPStatusFunction-*"])}]}}}

   :WPDistOriginRequestRole
   {:Type "AWS::IAM::Role"
    :Properties
    {:AssumeRolePolicyDocument
     {:Version "2012-10-17"
      :Statement
      {:Effect "Allow"
       :Principal
       {:Service ["edgelambda.amazonaws.com" "lambda.amazonaws.com"]}
       :Action "sts:AssumeRole"}}
     :ManagedPolicyArns
     ["arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
      (ref :WPDistOriginRequestPolicy)]}}

   :WPDistOriginRequestFunction
   {:Type "AWS::Lambda::Function"
    :Properties
    {:Description
     "Check whether the EC2 instance is running. If so, tell CloudFront to use its current IP as the origin. If not, start the instance or wait for it to start and show a loading page."
     :Handler "index.handler"
     :MemorySize 128
     :Role (arn :WPDistOriginRequestRole)
     :Runtime "nodejs12.x"
     :Code
     {:ZipFile
      (join ""
        ["const AUTH_HEADER ='" (ref :CloudFrontAuthorizationHeader) "';\n"
         "const INSTANCE_ID = '" (ref :WPServer) "';\n"
         "const INSTANCE_REGION = '" region "';\n\n"
         (slurp (io/resource "io/staticweb/open-wp/wp-dist-origin-request.js"))])}}}

   :WPDistOriginRequestFunctionVersion
   {:Type "AWS::Lambda::Version"
    :Properties
    {:Description
     "Check whether the EC2 instance is running. If so, tell CloudFront to use its current IP as the origin. If not, start the instance or wait for it to start and show a loading page."
     :FunctionName (ref :WPDistOriginRequestFunction)}}

   :WPStatusFunction
   {:Type "AWS::Lambda::Function"
    :Properties
    {:Description
     "Return the status of the WP instance. Start it if asked to."
     :Handler "index.handler"
     :MemorySize 128
     :Role (arn :WPDistOriginRequestRole)
     :Runtime "nodejs12.x"
     :Code
     {:ZipFile
      (join ""
        ["const INSTANCE_ID = '" (ref :WPServer) "';\n"
         "const INSTANCE_REGION = '" region "';\n\n"
         (slurp (io/resource "io/staticweb/open-wp/status.js"))])}}}

   :WPStatusFunctionVersion
   {:Type "AWS::Lambda::Version"
    :Properties
    {:Description
     "Return the status of the WP instance. Start it if asked to."
     :FunctionName (ref :WPStatusFunction)}}

   :WPServerDistribution
   {:Type "AWS::CloudFront::Distribution"
    :Properties
    {:DistributionConfig
     {:Aliases (fn-if :UsingGenericWPDomain
                 no-value
                 [(ref :WPDomainName)])
      :CacheBehaviors
      [{:AllowedMethods ["DELETE" "GET" "HEAD" "OPTIONS" "PATCH" "POST" "PUT"]
        :Compress true
        :DefaultTTL 0
        :ForwardedValues
        {:Cookies {:Forward "none"}
         :QueryString false}
        :LambdaFunctionAssociations
        [{:EventType "viewer-request"
          :IncludeBody true
          :LambdaFunctionARN (ref :WPStatusFunctionVersion)}]
        :MaxTTL 0
        :MinTTL 0
        :PathPattern "/_staticweb/status"
        :TargetOriginId "DummyOrigin"
        :ViewerProtocolPolicy "redirect-to-https"}]
      :DefaultCacheBehavior
      {:AllowedMethods ["DELETE" "GET" "HEAD" "OPTIONS" "PATCH" "POST" "PUT"]
       :Compress true
       :DefaultTTL 0
       :ForwardedValues
       {:Cookies {:Forward "all"}
        :Headers ["host"]
        :QueryString true}
       :LambdaFunctionAssociations
       [{:EventType "origin-request"
         :IncludeBody false
         :LambdaFunctionARN (ref :WPDistOriginRequestFunctionVersion)}]
       :MaxTTL 0
       :MinTTL 0
       :TargetOriginId "DummyOrigin"
       :ViewerProtocolPolicy "redirect-to-https"}
      :Enabled true
      :HttpVersion "http2"
      :Logging
      {:Bucket (get-att :LoggingBucket "DomainName")
       :IncludeCookies false
       :Prefix "wordpress-server/"}
      :Origins
      [{:CustomOriginConfig {:OriginProtocolPolicy "http-only"}
        :DomainName "static.staticweb.io"
        :Id "DummyOrigin"}]
      :ViewerCertificate
      (fn-if :UsingGenericWPDomain
        no-value
        {:AcmCertificateArn (ref :WPCertificateArn)
         :SslSupportMethod "sni-only"})}}}}

  :Outputs
  (outputs
    {:AdminLoginUrl
     [(sub "${AWS::StackName}-AdminLoginUrl")
      (join ""
        ["https://" (get-att :WPServerDistribution "DomainName") "/wp-admin/"])]
     :StaticSiteDomainName
     [(sub "${AWS::StackName}-StaticSiteDomainName")
      (get-att :StaticSiteDistribution "DomainName")]
     :WPServerDomainName
     [(sub "${AWS::StackName}-WPServerDomainName")
      (get-att :WPServerDistribution "DomainName")]}))

(write-template "cloudformation/staticweb-open-wp.template"
  open-wp-template)
