package myproject;

import com.pulumi.Pulumi;
import com.pulumi.core.Output;
import com.pulumi.deployment.InvokeOptions;

import java.util.*;
import com.pulumi.*;
import com.pulumi.oci.Core.InternetGateway;
import com.pulumi.oci.Core.InternetGatewayArgs;
import com.pulumi.oci.Core.ServiceGateway;
import com.pulumi.oci.Core.ServiceGatewayArgs;
import com.pulumi.oci.Core.Subnet;
import com.pulumi.oci.Core.SubnetArgs;
import com.pulumi.oci.Core.NatGateway;
import com.pulumi.oci.Core.NatGatewayArgs;
import com.pulumi.oci.Core.RouteTable;
import com.pulumi.oci.Core.RouteTableArgs;
import com.pulumi.oci.Core.SecurityList;
import com.pulumi.oci.Core.SecurityListArgs;
import com.pulumi.oci.Core.Vcn;
import com.pulumi.oci.Core.VcnArgs;
import com.pulumi.oci.Core.inputs.ServiceGatewayServiceArgs;
import com.pulumi.oci.Core.outputs.GetServiceGatewaysServiceGatewayService;
import com.pulumi.oci.Core.outputs.GetServicesService;
import com.pulumi.oci.Core.CoreFunctions;
import com.pulumi.oci.Identity.IdentityFunctions;
import com.pulumi.oci.Identity.inputs.GetRegionsArgs;
import com.pulumi.oci.Limits.outputs.GetServicesResult;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.oci.Core.inputs.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class App {
    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    private static final String TIMESTAMP_SUFFIX = String
            .valueOf(System.currentTimeMillis() % TimeUnit.SECONDS.toMillis(10L));

    private static void stack(Context ctx) {

        String tag_path_source = "jb-dlp/pulumi/java/network";
        Map<String, Object> freeFormTagMap = new HashMap<>();
        freeFormTagMap.put("triggered-by", tag_path_source);

        var region = ctx.config().get("target_region").orElse(ctx.config("oci").require("region"));
        // var region =ctx.config().get("target_region").orElse("us-ashburn-1");
        var tenancyOcid = ctx.config("oci").require("tenancyOcid");
        var fingerprint = ctx.config("oci").require("fingerprint");
        var userOcid = ctx.config("oci").require("userOcid");
        var oci_priv_key = ctx.config("oci").require("privateKey");

        String targetCompartmentId = ctx.config().require("compartmentOcid");
        String ALL_NETS = "0.0.0.0/0";
        var DEFAULT_VCN_CIDR = ctx.config().get("vcn_cidr").orElse("10.0.0.0/16");
        var DEFAULT_ENDPOINT_SUBNET_CDIR = ctx.config().get("oke_endpoint_cidr").orElse("10.0.0.0/24");
        var DEFAULT_SVCS_SUBNET_CDIR = ctx.config().get("oke_svcs_cidr").orElse("10.0.1.0/24");
        var DEFAULT_WORKERNODE_SUBNET_CDIR = ctx.config().get("worker_node_cidr").orElse("10.0.2.0/24");
        var DNS_LABEL = ctx.config().get("dns_label").orElse("javagradle");

        final var ociRegion = new com.pulumi.oci.Provider("oci",
                com.pulumi.oci.ProviderArgs.builder()
                        .tenancyOcid(tenancyOcid)
                        .fingerprint(fingerprint)
                        .userOcid(userOcid)
                        .privateKey(oci_priv_key)
                        .region(region)
                        .build());

        // TODO: get region short names
        // var shortRegionName = IdentityFunctions.getRegions(GetRegionsArgs.builder().)

        // VCN
        // String ENDPOINT_PORTS =
        VcnArgs vcnArgs = VcnArgs.builder()
                .cidrBlock(DEFAULT_VCN_CIDR)
                .displayName(String.format(
                        "vcn-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                .compartmentId(targetCompartmentId)
                .dnsLabel(DNS_LABEL)
                .freeformTags(freeFormTagMap)
                .build();
        Vcn vcn = new Vcn("vcn", vcnArgs,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        ///////////////////////// GATEWAYS ////////////////////////////
        InternetGatewayArgs ig_args = InternetGatewayArgs.builder()
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "igw-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                .vcnId(vcn.getId())
                .freeformTags(freeFormTagMap)
                .build();

        InternetGateway gateway_internet = new InternetGateway("i_gw", ig_args,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        
        GetServicesPlainArgs funcArgs= GetServicesPlainArgs.builder()
            .filters(GetServicesFilter.builder()
                .name("name")
                .values("All .* Services In Oracle Services Network")
                .regex(true)
                .build())
            .build();
        InvokeOptions inv_opt = new InvokeOptions(null, ociRegion, null);                        
        
        var serviceId = Output
                .of(CoreFunctions.getServicesPlain(funcArgs, inv_opt).thenApply(result -> result.services().get(0).id()));

        var serviceDesc = Output
                .of(CoreFunctions.getServicesPlain(funcArgs, inv_opt).thenApply(result -> result.services().get(0).description()));        

        var serviceCidr = Output
                .of(CoreFunctions.getServicesPlain(funcArgs, inv_opt).thenApply(result -> result.services().get(0).cidrBlock()));        

        ServiceGatewayServiceArgs svc_gt_svc_args = ServiceGatewayServiceArgs.builder()
                .serviceId(serviceId)
                .build();
        
        // ctx.export("services_id", Output.of(CoreFunctions.getServicesPlain().thenApply(result -> result.services())));
        ctx.export("service id", serviceId );
        ctx.export("service desc", serviceDesc );
        ctx.export("service cidr", serviceCidr);
        // ctx.log().info(String.format("Service Gateway Details \n, service id : %s , description: %s, cidr: %s", serviceId, serviceDesc, serviceCidr));

        List<ServiceGatewayServiceArgs> services = Arrays.asList(svc_gt_svc_args);

        ServiceGatewayArgs gtw_service_args = ServiceGatewayArgs.builder()
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "gw-Service-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                .routeTableId("")
                .services(services)
                .vcnId(vcn.getId())
                .freeformTags(freeFormTagMap)
                .build();

        // ServiceGateway gateway_service = oci.core.ServiceGateway("sv_gw",
        // compartment_id=compartment_id,
        // display_name='',
        // route_table_id="",
        // services=[oci.core.ServiceGatewayServiceArgs(
        // service_id=all_services.services[0].id)],
        // vcn_id=vcn.id
        // );
        ServiceGateway gateway_service = new ServiceGateway("sv_gw", gtw_service_args,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        NatGatewayArgs gtw_nat_args = NatGatewayArgs.builder()
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "nat-gw-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                .vcnId(vcn.getId())
                .freeformTags(freeFormTagMap)
                .build();

        NatGateway gateway_nat = new NatGateway("nat_gw", gtw_nat_args,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        ////////////////////
        // # Routing Rules
        // ////////////////

        RouteTableRouteRuleArgs route_rule_to_internet_all = RouteTableRouteRuleArgs.builder()
                .description("traffic to/from internet")
                .destination(ALL_NETS)
                .destinationType("CIDR_BLOCK")
                .networkEntityId(gateway_internet.getId())
                .build();


        RouteTableRouteRuleArgs route_rule_to_oci_services_all = RouteTableRouteRuleArgs.builder()
                .description("Auto-generated at Service Gateway creation: All Services in region to Service Gateway")
                .destination(serviceCidr)
                .destinationType("SERVICE_CIDR_BLOCK")
                .networkEntityId(gateway_service.getId())
                .build();

        RouteTableRouteRuleArgs route_rule_to_nat_internet = RouteTableRouteRuleArgs.builder()
                .description("traffic to internet")
                .destination(ALL_NETS)
                .destinationType("CIDR_BLOCK")
                .networkEntityId(gateway_nat.getId())
                .build();

        // ###
        // # Public Route Table
        // ###
        RouteTableArgs rt_pub_args = RouteTableArgs.builder()
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "pub-rt-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                // .displayName("public-routetable-" + env[ID])
                .routeRules(route_rule_to_internet_all)
                .vcnId(vcn.getId())
                .build();

        RouteTable route_table_public = new RouteTable("public_rt", rt_pub_args,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        // ###
        // # Public Route Table
        // ###
        RouteTableArgs rt_ocisvcs_args = RouteTableArgs.builder()
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "ocisvcs-rt-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                .routeRules(Arrays.asList(route_rule_to_oci_services_all, route_rule_to_nat_internet))
                .vcnId(vcn.getId())
                .build();

        RouteTable route_table_ocisvcs = new RouteTable("oci_rt", rt_ocisvcs_args,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        // RouteTable route_table_ocisvcs = RouteTable("oci_rt",
        // compartment_id=compartment_id,
        // display_name="ocisvcs-routetable-" + env[ID],
        // route_rules=[route_rule_to_oci_services_all, route_rule_to_nat_internet],
        // vcn_id=vcn.id,
        // )
        // ##############
        // # Security Lists
        // ####
        // # Empty Sec list - No ingress Rules / Egress Rules
        // // // //
        // Security Lists - OKE Loadbalancer Services Ingress Rules
        List<SecurityListIngressSecurityRuleArgs> ingressSecurityRulesSVCS = new ArrayList<SecurityListIngressSecurityRuleArgs>();
        ingressSecurityRulesSVCS.add(createTCPIngressSecurityList("External access to LBs port 80", 80, ALL_NETS));
        ingressSecurityRulesSVCS.add(createTCPIngressSecurityList("External access to LBs port 443", 443, ALL_NETS));

        SecurityListArgs secListArgs = SecurityListArgs.builder()
                .compartmentId(targetCompartmentId)
                .displayName("svclbseclist-pulumi-java")

                .vcnId(vcn.getId())
                .egressSecurityRules(createTCPEgressSecurityList("All traffic to Internet", ALL_NETS))
                .ingressSecurityRules(ingressSecurityRulesSVCS)
                .freeformTags(freeFormTagMap)
                .build();

        SecurityList security_list_svcs = new SecurityList("svcs_sec_list", secListArgs,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        // // // // // // //
        // Security Lists - OKE Endpoint Subnet Rules
        List<SecurityListEgressSecurityRuleArgs> k8sEgressSecList = new ArrayList<SecurityListEgressSecurityRuleArgs>();
        k8sEgressSecList.add(createPathDiscoveryEgressSecurityList(DEFAULT_WORKERNODE_SUBNET_CDIR));
        // k8sEgressSecList.add(createTCPEgressSecurityList("Allow Kubernetes Control
        // Plane to communicate with
        // OKE","all-iad-services-in-oracle-services-network","SERVICE_CIDR_BLOCK",443));
        k8sEgressSecList.add(createTCPEgressSecurityList("Allow Kubernetes Control Plane to communicate with OKE",
        serviceCidr, "SERVICE_CIDR_BLOCK", 443));
        k8sEgressSecList
                .add(createTCPEgressSecurityList("All traffic to worker nodes", DEFAULT_WORKERNODE_SUBNET_CDIR));

        List<SecurityListIngressSecurityRuleArgs> k8sIngressSecList = new ArrayList<SecurityListIngressSecurityRuleArgs>();
        k8sIngressSecList
                .add(createTCPIngressSecurityList("External access to Kubernetes API endpoint", 6443, ALL_NETS));
        k8sIngressSecList.add(createTCPIngressSecurityList("Kubernetes worker to control plane communication", 12250,
                DEFAULT_WORKERNODE_SUBNET_CDIR));
        k8sIngressSecList.add(createTCPIngressSecurityList("Kubernetes worker to Kubernetes API endpoint communication",
                6443, DEFAULT_WORKERNODE_SUBNET_CDIR));
        k8sIngressSecList.add(createPathDiscoveryIngressSecurityList(DEFAULT_WORKERNODE_SUBNET_CDIR));

        SecurityListArgs k8sSecListArgs = SecurityListArgs.builder()
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "k8sSecListArgs-%s-%s",
                        region.toString(),
                        TIMESTAMP_SUFFIX))
                .egressSecurityRules(k8sEgressSecList)
                .ingressSecurityRules(k8sIngressSecList)
                .vcnId(vcn.getId())
                .build();
        SecurityList k8sSeclist = new SecurityList("endpoint_sec_list", k8sSecListArgs,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        // ###
        // # Security List - OKE Worker Nodes Rules
        // ##

        List<SecurityListEgressSecurityRuleArgs> workersEgressSecList = new ArrayList<SecurityListEgressSecurityRuleArgs>();
        workersEgressSecList.add(createTCPEgressSecurityList("Kubernetes worker to control plane communication", 12250,
                DEFAULT_ENDPOINT_SUBNET_CDIR));
        workersEgressSecList.add(createTCPEgressSecurityList("Worker Nodes access to Internet", ALL_NETS));
        workersEgressSecList.add(
                createTCPEgressSecurityList("Access to Kubernetes API Endpoint", 6443, DEFAULT_ENDPOINT_SUBNET_CDIR));
        workersEgressSecList.add(createPathDiscoveryEgressSecurityList(DEFAULT_WORKERNODE_SUBNET_CDIR));
        workersEgressSecList.add(createPathDiscoveryEgressSecurityList(ALL_NETS));
        // workersEgressSecList.add(createTCPEgressSecurityList("Allow nodes to
        // communicate with OKE to ensure correct start-up and continued
        // functioning","all-iad-services-in-oracle-services-network","SERVICE_CIDR_BLOCK",443));
        workersEgressSecList.add(createTCPEgressSecurityList(
                "Allow nodes to communicate with OKE to ensure correct start-up and continued functioning",
                serviceCidr, "SERVICE_CIDR_BLOCK", 443));
        workersEgressSecList.add(createTCPEgressSecurityList(
                "Allow pods on one worker node to communicate with pods on other worker nodes",
                DEFAULT_WORKERNODE_SUBNET_CDIR));
        workersEgressSecList
                .add(createTCPEgressSecurityList("Allow pods to communicate with LB Subnet", DEFAULT_SVCS_SUBNET_CDIR));

        List<SecurityListIngressSecurityRuleArgs> workersIngressSecList = new ArrayList<SecurityListIngressSecurityRuleArgs>();
        workersIngressSecList.add(createPathDiscoveryIngressSecurityList(DEFAULT_ENDPOINT_SUBNET_CDIR));
        workersIngressSecList.add(createTCPIngressSecurityList(
                "Allow pods on one worker node to communicate with pods on other worker nodes",
                DEFAULT_WORKERNODE_SUBNET_CDIR));
        workersIngressSecList.add(createTCPIngressSecurityList("TCP access from Kubernetes Control Plane", null,
                DEFAULT_ENDPOINT_SUBNET_CDIR));
        workersIngressSecList.add(createTCPIngressSecurityList("TCP from Loadbalancer subnet to Nodes Subnet", null,
                DEFAULT_SVCS_SUBNET_CDIR));

        // # Sec_list WorkerNodes Subnet
        var workersSecListArgs = SecurityListArgs.builder()
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "workersSecListArgs-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                .egressSecurityRules(workersEgressSecList)
                .ingressSecurityRules(workersIngressSecList)
                .vcnId(vcn.getId())
                .build();

        SecurityList workersSeclist = new SecurityList("workers_sec_list", workersSecListArgs,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        // #### END NODE SEC LIST
        // ######
        // ######
        // # 2 Regional Public Subnets , 1 Regional Private Subnet (Workers)
        // ##### Load Balancer Public Subnet
        SubnetArgs svcs_subnet_args = SubnetArgs.builder()
                .cidrBlock(DEFAULT_SVCS_SUBNET_CDIR)
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "okelb-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                .dhcpOptionsId(vcn.defaultDhcpOptionsId())
                .dnsLabel("okesvcs")
                .routeTableId(route_table_public.getId())
                .securityListIds(security_list_svcs.getId().applyValue(List::of))
                .vcnId(vcn.getId())
                .freeformTags(freeFormTagMap)
                .build();

        Subnet subnet_svcs = new Subnet("svcs_subnet", svcs_subnet_args,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());
        // ##### Worker Nodes Private Subnet
        SubnetArgs workernodes_subnet_args = SubnetArgs.builder()
                .prohibitPublicIpOnVnic(true)
                .cidrBlock(DEFAULT_WORKERNODE_SUBNET_CDIR)
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "okeWorkers-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                .dhcpOptionsId(vcn.defaultDhcpOptionsId())
                .dnsLabel("workers")
                .routeTableId(route_table_ocisvcs.getId())
                .securityListIds(workersSeclist.getId().applyValue(List::of))
                .vcnId(vcn.getId())
                .freeformTags(freeFormTagMap)
                .build();
        Subnet subnet_nodes = new Subnet("nodes_subnet", workernodes_subnet_args,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        // ##### EndPoints Public Subnet
        SubnetArgs endpoints_subnet_args = SubnetArgs.builder()
                // .prohibitPublicIpOnVnic(true)
                .cidrBlock(DEFAULT_ENDPOINT_SUBNET_CDIR)
                .compartmentId(targetCompartmentId)
                .displayName(String.format(
                        "okeEndpoints-%s-%s",
                        ctx.projectName(),
                        TIMESTAMP_SUFFIX))
                .dhcpOptionsId(vcn.defaultDhcpOptionsId())
                .dnsLabel("endpoints")
                .routeTableId(route_table_public.getId())
                .securityListIds(k8sSeclist.getId().applyValue(List::of))
                .vcnId(vcn.getId())
                .freeformTags(freeFormTagMap)
                .build();

        Subnet subnet_endpoint = new Subnet("endpoint_subnet", endpoints_subnet_args,
                CustomResourceOptions.builder()
                        .provider(ociRegion)
                        .build());

        // # Exports Pulumi

        ctx.export("VCN ", vcn.getId());
        ctx.export("Public Subnet - LB Services", subnet_svcs.getId());
        ctx.export("Public Subnet - OKE Endpoint", subnet_endpoint.getId());
        ctx.export("Private Subnet - OKE Workernodes", subnet_nodes.getId());
    }

    //
    // Helpers
    //
    private static SecurityListIngressSecurityRuleArgs createTCPIngressSecurityList(String description,
            String source_address) {
        System.out.println("Creating Sec list 2 Params: " + description);
        SecurityListIngressSecurityRuleArgs secList = SecurityListIngressSecurityRuleArgs.builder()
                .description(description)
                .protocol("all")
                .source(source_address)
                .sourceType("CIDR_BLOCK")
                .build();
        return secList;
    }

    private static SecurityListIngressSecurityRuleArgs createTCPIngressSecurityList(String description, Integer port,
            String source_address) {
        System.out.println("Creating Sec list 3 Params: " + description);
        // SecurityListIngressSecurityRuleTcpOptionsArgs args;
        // if (port != null){
        // args =SecurityListIngressSecurityRuleTcpOptionsArgs.builder()
        // .max(port)
        // .min(port)
        // .build();
        // }
        // else{
        // args = SecurityListIngressSecurityRuleTcpOptionsArgs.builder().build();
        // }

        // SecurityListIngressSecurityRuleArgs secList =
        // SecurityListIngressSecurityRuleArgs.builder()
        // .description(description)
        // .protocol("6")
        // .source(source_address)
        // .sourceType("CIDR_BLOCK")
        // .tcpOptions(args)
        // .build();

        if (port != null) {
            return SecurityListIngressSecurityRuleArgs.builder()
                    .description(description)
                    .protocol("6")
                    .source(source_address)
                    .sourceType("CIDR_BLOCK")
                    .tcpOptions(SecurityListIngressSecurityRuleTcpOptionsArgs.builder()
                            .max(port)
                            .min(port)
                            .build())
                    .build();
        } else {
            return SecurityListIngressSecurityRuleArgs.builder()
                    .description(description)
                    .protocol("6")
                    .source(source_address)
                    .sourceType("CIDR_BLOCK")
                    .build();
        }
    }

    private static SecurityListIngressSecurityRuleArgs createPathDiscoveryIngressSecurityList(String source_address) {
        SecurityListIngressSecurityRuleArgs secList = SecurityListIngressSecurityRuleArgs.builder()
                .description("Path Discovery")
                .protocol("1")
                .source(source_address)
                .sourceType("CIDR_BLOCK")
                .icmpOptions(SecurityListIngressSecurityRuleIcmpOptionsArgs.builder()
                        .code(4)
                        .type(3)
                        .build())
                .build();
        return secList;
    }

    private static SecurityListEgressSecurityRuleArgs createPathDiscoveryEgressSecurityList(
            String destination_address) {
        SecurityListEgressSecurityRuleArgs secList = SecurityListEgressSecurityRuleArgs.builder()
                .description("Path Discovery")
                .protocol("1")
                .destination(destination_address)
                .destinationType("CIDR_BLOCK")
                .icmpOptions(SecurityListEgressSecurityRuleIcmpOptionsArgs.builder()
                        .code(4)
                        .type(3)
                        .build())
                .build();
        return secList;
    }

    private static SecurityListEgressSecurityRuleArgs createTCPEgressSecurityList(String description,
            String destination_address) {
        SecurityListEgressSecurityRuleArgs secList = SecurityListEgressSecurityRuleArgs.builder()
                .description(description)
                .protocol("all")
                .destination(destination_address)
                .destinationType("CIDR_BLOCK")
                .build();
        return secList;
    }

    private static SecurityListEgressSecurityRuleArgs createTCPEgressSecurityList(String description, Integer port,
            String destination_address) {
        return createTCPEgressSecurityList(description, destination_address, "CIDR_BLOCK", port);
    }

    private static SecurityListEgressSecurityRuleArgs createTCPEgressSecurityList(String description,
            Output<String> destination_address, String destinationType, Integer port) {
        SecurityListEgressSecurityRuleArgs secList = SecurityListEgressSecurityRuleArgs.builder()
                .description(description)
                .protocol("6")
                .destination(destination_address)
                .destinationType(destinationType)
                .tcpOptions(SecurityListEgressSecurityRuleTcpOptionsArgs.builder()
                        .max(port)
                        .min(port)
                        .build())
                .build();
        return secList;
    }

    private static SecurityListEgressSecurityRuleArgs createTCPEgressSecurityList(String description,
            String destination_address, String destinationType, Integer port) {
        SecurityListEgressSecurityRuleArgs secList = SecurityListEgressSecurityRuleArgs.builder()
                .description(description)
                .protocol("6")
                .destination(destination_address)
                .destinationType(destinationType)
                .tcpOptions(SecurityListEgressSecurityRuleTcpOptionsArgs.builder()
                        .max(port)
                        .min(port)
                        .build())
                .build();
        return secList;
    }
}
